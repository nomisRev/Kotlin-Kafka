package io.github.nomisrev.kafka

import io.github.nomisRev.kafka.Admin
import io.github.nomisRev.kafka.AdminSettings
import io.github.nomisRev.kafka.AutoOffsetReset
import io.github.nomisRev.kafka.ProducerSettings
import io.github.nomisRev.kafka.createTopic
import io.github.nomisRev.kafka.deleteTopic
import io.github.nomisRev.kafka.describeTopic
import io.github.nomisRev.kafka.produce
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverSettings
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.Properties
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
abstract class KafkaSpec(body: KafkaSpec.() -> Unit = {}) : StringSpec() {
  init {
    body()
  }
  
  private val transactionTimeoutInterval = 1.seconds
  private val consumerPollingTimeout = 1.seconds
  
  private val imageVersion = "7.0.1"
  
  private val kafkaImage: DockerImageName =
    if (System.getProperty("os.arch") == "aarch64") DockerImageName.parse("niciqy/cp-kafka-arm64:$imageVersion")
      .asCompatibleSubstituteFor("confluentinc/cp-kafka")
    else DockerImageName.parse("confluentinc/cp-kafka:$imageVersion")
  
  private val container: KafkaContainer = autoClose(
    KafkaContainer(kafkaImage)
      .withExposedPorts(9092, 9093)
      .withNetworkAliases("broker")
      .withEnv("KAFKA_HOST_NAME", "broker")
      .withEnv("KAFKA_CONFLUENT_LICENSE_TOPIC_REPLICATION_FACTOR", "1")
      .withEnv("KAFKA_CONFLUENT_BALANCER_TOPIC_REPLICATION_FACTOR", "1")
      .withEnv(
        "KAFKA_TRANSACTION_ABORT_TIMED_OUT_TRANSACTION_CLEANUP_INTERVAL_MS",
        transactionTimeoutInterval.inWholeMilliseconds.toString()
      )
      .withEnv("KAFKA_AUTHORIZER_CLASS_NAME", "kafka.security.authorizer.AclAuthorizer")
      .withEnv("KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND", "true")
      .also { container -> container.start() }
  )
  
  private fun adminProperties(): Properties = Properties().apply {
    put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers)
    put(AdminClientConfig.CLIENT_ID_CONFIG, "test-kafka-admin-client")
    put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")
    put(AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, "10000")
  }
  
  fun adminSettings(): AdminSettings =
    AdminSettings(container.bootstrapServers, adminProperties())
  
  inline fun <A> admin(body: Admin.() -> A): A =
    Admin(adminSettings()).use(body)
  
  fun receiverSetting(): ReceiverSettings<String, String> =
    ReceiverSettings(
      bootstrapServers = container.bootstrapServers,
      keyDeserializer = StringDeserializer(),
      valueDeserializer = StringDeserializer(),
      groupId = "test-group-id",
      autoOffsetReset = AutoOffsetReset.Earliest,
      pollTimeout = consumerPollingTimeout
    )
  
  fun producerSettings(): ProducerSettings<String, String> =
    ProducerSettings(
      bootstrapServers = container.bootstrapServers,
      keyDeserializer = StringSerializer(),
      valueDeserializer = StringSerializer(),
      other = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers)
        put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000.toString())
        put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000.toString())
      }
    )
  
  private fun nextTopicName(): String =
    "topic-${UUID.randomUUID()}"
  
  suspend fun <A> withTopic(
    topicConfig: Map<String, String> = emptyMap(),
    partitions: Int = 1,
    replicationFactor: Short = 1,
    action: suspend Admin.(NewTopic) -> A,
  ): A {
    val topic = NewTopic(nextTopicName(), partitions, replicationFactor).configs(topicConfig)
    return admin {
      createTopic(topic)
      try {
        action(topic)
      } finally {
        deleteTopic(topic.name())
      }
    }
  }
  
  @JvmName("publishPairsToKafka")
  suspend fun publishToKafka(
    topic: NewTopic,
    messages: Iterable<Pair<String, String>>,
  ): Unit =
    messages.asFlow()
      .map { (key, value) ->
        ProducerRecord(topic.name(), key, value)
      }.produce(producerSettings())
      .collect()
  
  suspend fun publishToKafka(messages: Iterable<ProducerRecord<String, String>>): Unit =
    messages.asFlow()
      .produce(producerSettings())
      .collect()
  
  suspend fun <K, V> KafkaReceiver<K, V>.committedCount(topic: String): Long =
    admin {
      val description = describeTopic(topic) ?: throw IllegalStateException("Topic $topic not found")
      val topicPartitions = description.partitions().map {
        TopicPartition(topic, it.partition())
      }.toSet()
      
      withConsumer {
        committed(topicPartitions)
          .filter { (_, offset) -> offset != null && offset.offset() > 0 }
          .map { (_, metadata) -> metadata.offset() }
          .sum()
      }
    }
}