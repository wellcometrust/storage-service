package uk.ac.wellcome.platform.archive.indexer.bags

import akka.actor.ActorSystem
import com.sksamuel.elastic4s.Index
import com.typesafe.config.Config
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import uk.ac.wellcome.elasticsearch.ElasticsearchIndexCreator
import uk.ac.wellcome.elasticsearch.typesafe.ElasticBuilder
import weco.json.JsonUtil._
import weco.messaging.typesafe.{
  AlpakkaSqsWorkerConfigBuilder,
  SQSBuilder
}
import weco.monitoring.cloudwatch.CloudWatchMetrics
import weco.monitoring.typesafe.CloudWatchBuilder
import uk.ac.wellcome.platform.archive.bag_tracker.client.AkkaBagTrackerClient
import weco.typesafe.WellcomeTypesafeApp
import weco.typesafe.config.builders.AkkaBuilder
import weco.typesafe.config.builders.EnrichConfig._

import scala.concurrent.ExecutionContextExecutor

object Main extends WellcomeTypesafeApp {
  runWithConfig { config: Config =>
    implicit val actorSystem: ActorSystem = AkkaBuilder.buildActorSystem()

    implicit val executionContext: ExecutionContextExecutor =
      actorSystem.dispatcher

    implicit val metrics: CloudWatchMetrics =
      CloudWatchBuilder.buildCloudWatchMetrics(config)

    implicit val sqsClient: SqsAsyncClient =
      SQSBuilder.buildSQSAsyncClient(config)

    val index = Index(name = config.requireString("es.bags.index-name"))
    info(s"Writing bags to index $index")

    info(s"Creating the Elasticsearch index mapping")
    val elasticClient = ElasticBuilder.buildElasticClient(config)

    val indexCreator = new ElasticsearchIndexCreator(
      elasticClient = elasticClient,
      index = index,
      config = BagsIndexConfig.config
    )

    indexCreator.create

    val bagIndexer = new BagIndexer(
      client = elasticClient,
      index = index
    )

    val bagTrackerClient = new AkkaBagTrackerClient(
      trackerHost = config.requireString("bags.tracker.host")
    )

    new BagIndexerWorker(
      config = AlpakkaSqsWorkerConfigBuilder.build(config),
      indexer = bagIndexer,
      metricsNamespace = config.requireString("aws.metrics.namespace"),
      bagTrackerClient = bagTrackerClient
    )
  }
}
