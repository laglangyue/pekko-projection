/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.projection.kafka.internal

import java.lang.{ Long => JLong }
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.function.Supplier

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.typed.ActorSystem
import pekko.annotation.InternalApi
import pekko.kafka.ConsumerSettings
import pekko.kafka.RestrictedConsumer
import pekko.kafka.Subscriptions
import pekko.kafka.scaladsl.Consumer
import pekko.kafka.scaladsl.PartitionAssignmentHandler
import pekko.projection.MergeableOffset
import pekko.projection.OffsetVerification
import pekko.projection.OffsetVerification.VerificationFailure
import pekko.projection.OffsetVerification.VerificationSuccess
import pekko.projection.javadsl
import pekko.projection.kafka.KafkaOffsets
import pekko.projection.kafka.KafkaOffsets.keyToPartition
import pekko.projection.scaladsl
import pekko.stream.scaladsl.Keep
import pekko.stream.scaladsl.Source
import pekko.util.FutureConverters._
import pekko.util.OptionConverters._
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.record.TimestampType

/**
 * INTERNAL API
 */
@InternalApi private[projection] object KafkaSourceProviderImpl {
  private[kafka] type ReadOffsets = () => Future[Option[MergeableOffset[JLong]]]
  private val EmptyTps: Set[TopicPartition] = Set.empty
}

/**
 * INTERNAL API
 */
@InternalApi private[projection] class KafkaSourceProviderImpl[K, V](
    system: ActorSystem[_],
    settings: ConsumerSettings[K, V],
    topics: Set[String],
    metadataClientFactory: () => MetadataClientAdapter,
    sourceProviderSettings: KafkaSourceProviderSettings)
    extends javadsl.SourceProvider[MergeableOffset[JLong], ConsumerRecord[K, V]]
    with scaladsl.SourceProvider[MergeableOffset[JLong], ConsumerRecord[K, V]]
    with javadsl.VerifiableSourceProvider[MergeableOffset[JLong], ConsumerRecord[K, V]]
    with scaladsl.VerifiableSourceProvider[MergeableOffset[JLong], ConsumerRecord[K, V]]
    with javadsl.MergeableOffsetSourceProvider[MergeableOffset[JLong], ConsumerRecord[K, V]]
    with scaladsl.MergeableOffsetSourceProvider[MergeableOffset[JLong], ConsumerRecord[K, V]] {

  import KafkaSourceProviderImpl._

  private implicit val executionContext: ExecutionContext = system.executionContext

  private[kafka] val partitionHandler = new ProjectionPartitionHandler
  private val scheduler = system.classicSystem.scheduler
  private val subscription = Subscriptions.topics(topics).withPartitionAssignmentHandler(partitionHandler)
  private[projection] var control: Option[Consumer.Control] = None
  // assigned partitions is only ever mutated by consumer rebalance partition handler executed in the Kafka consumer
  // poll thread in the Alpakka Kafka `KafkaConsumerActor`
  @volatile private var assignedPartitions: Set[TopicPartition] = Set.empty

  protected[internal] def _source(
      readOffsets: ReadOffsets,
      numPartitions: Int,
      metadataClient: MetadataClientAdapter): Source[ConsumerRecord[K, V], Consumer.Control] =
    Consumer
      .plainPartitionedManualOffsetSource(settings, subscription, getOffsetsOnAssign(readOffsets, metadataClient))
      .flatMapMerge(numPartitions,
        {
          case (_, partitionedSource) => partitionedSource
        })

  override def source(readOffsets: ReadOffsets): Future[Source[ConsumerRecord[K, V], NotUsed]] = {
    // get the total number of partitions to configure the `breadth` parameter, or we could just use a really large
    // number.  i don't think using a large number would present a problem.
    val metadataClient = metadataClientFactory()
    val numPartitionsF = metadataClient.numPartitions(topics)
    numPartitionsF.failed.foreach(_ => metadataClient.stop())
    numPartitionsF.map { numPartitions =>
      _source(readOffsets, numPartitions, metadataClient)
        .mapMaterializedValue { m =>
          control = Some(m)
          m
        }
        .watchTermination()(Keep.right)
        .mapMaterializedValue { terminated =>
          terminated.onComplete(_ => metadataClient.stop())
          NotUsed
        }
    }
  }

  override def source(readOffsets: Supplier[CompletionStage[Optional[MergeableOffset[JLong]]]])
      : CompletionStage[pekko.stream.javadsl.Source[ConsumerRecord[K, V], NotUsed]] = {
    source(() => readOffsets.get().asScala.map(_.toScala)).map(_.asJava).asJava
  }

  override def extractOffset(record: ConsumerRecord[K, V]): MergeableOffset[JLong] =
    KafkaOffsets.toMergeableOffset(record)

  override def verifyOffset(offsets: MergeableOffset[JLong]): OffsetVerification = {
    if (KafkaOffsets.partitions(offsets).forall(assignedPartitions.contains))
      VerificationSuccess
    else
      VerificationFailure(
        "The offset contains Kafka topic partitions that were revoked or lost in a previous rebalance")
  }

  override def extractCreationTime(record: ConsumerRecord[K, V]): Long = {
    if (record.timestampType() == TimestampType.CREATE_TIME)
      record.timestamp()
    else
      0L
  }

  private def getOffsetsOnAssign(
      readOffsets: ReadOffsets,
      metadataClient: MetadataClientAdapter): Set[TopicPartition] => Future[Map[TopicPartition, Long]] =
    (assignedTps: Set[TopicPartition]) => {
      val delay = sourceProviderSettings.readOffsetDelay
      pekko.pattern.after(delay, scheduler) {
        readOffsets()
          .flatMap {
            case Some(groupOffsets) =>
              val filteredMap = groupOffsets.entries.collect {
                case (topicPartitionKey, offset) if assignedTps.contains(keyToPartition(topicPartitionKey)) =>
                  keyToPartition(topicPartitionKey) -> (offset.asInstanceOf[Long] + 1L)
              }
              Future.successful(filteredMap)
            case None => metadataClient.getBeginningOffsets(assignedTps)
          }
          .recover {
            case ex => throw new RuntimeException("External offsets could not be retrieved", ex)
          }
      }
    }

  private[kafka] class ProjectionPartitionHandler extends PartitionAssignmentHandler {
    override def onRevoke(revokedTps: Set[TopicPartition], consumer: RestrictedConsumer): Unit =
      assignedPartitions = assignedPartitions.diff(revokedTps)

    override def onAssign(assignedTps: Set[TopicPartition], consumer: RestrictedConsumer): Unit =
      assignedPartitions = assignedTps

    override def onLost(lostTps: Set[TopicPartition], consumer: RestrictedConsumer): Unit =
      assignedPartitions = assignedPartitions.diff(lostTps)

    override def onStop(currentTps: Set[TopicPartition], consumer: RestrictedConsumer): Unit =
      assignedPartitions = EmptyTps
  }
}
