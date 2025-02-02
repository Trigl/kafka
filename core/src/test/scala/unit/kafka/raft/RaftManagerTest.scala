/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.raft

import java.util.concurrent.CompletableFuture
import java.util.Properties

import kafka.raft.KafkaRaftManager.RaftIoThread
import kafka.server.{KafkaConfig, MetaProperties}
import kafka.tools.TestRaftServer.ByteArraySerde
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.Uuid
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.utils.Time
import org.apache.kafka.raft.KafkaRaftClient
import org.apache.kafka.raft.RaftConfig
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test
import org.mockito.Mockito._

class RaftManagerTest {

  private def instantiateRaftManagerWithConfigs(processRoles: String, nodeId:String) = {
    def configWithProcessRolesAndNodeId(processRoles: String, nodeId: String): KafkaConfig = {
      val props = new Properties
      props.setProperty(KafkaConfig.ProcessRolesProp, processRoles)
      props.setProperty(KafkaConfig.NodeIdProp, nodeId)
      props.setProperty(KafkaConfig.ListenersProp, "PLAINTEXT://localhost:9093")
      props.setProperty(KafkaConfig.ControllerListenerNamesProp, "PLAINTEXT")
      props.setProperty(RaftConfig.QUORUM_VOTERS_CONFIG, nodeId.concat("@localhost:9093"))
      if (processRoles.contains("broker"))
        props.setProperty(KafkaConfig.InterBrokerListenerNameProp, "PLAINTEXT")
        props.setProperty(KafkaConfig.AdvertisedListenersProp, "PLAINTEXT://localhost:9092")
      new KafkaConfig(props)
    }

    val config = configWithProcessRolesAndNodeId(processRoles, nodeId)
    val topicId = new Uuid(0L, 2L)
    val metaProperties = MetaProperties(
      clusterId = Uuid.randomUuid.toString,
      nodeId = config.nodeId
    )

    new KafkaRaftManager[Array[Byte]](
      metaProperties,
      config,
      new ByteArraySerde,
      new TopicPartition("__taft_id_test", 0),
      topicId,
      Time.SYSTEM,
      new Metrics(Time.SYSTEM),
      Option.empty,
      CompletableFuture.completedFuture(RaftConfig.parseVoterConnections(config.quorumVoters))
    )
  }

  @Test
  def testSentinelNodeIdIfBrokerRoleOnly(): Unit = {
    val raftManager = instantiateRaftManagerWithConfigs("broker", "1")
    assertFalse(raftManager.client.nodeId.isPresent)
    raftManager.shutdown()
  }

  @Test
  def testNodeIdPresentIfControllerRoleOnly(): Unit = {
    val raftManager = instantiateRaftManagerWithConfigs("controller", "1")
    assertTrue(raftManager.client.nodeId.getAsInt == 1)
    raftManager.shutdown()
  }

  @Test
  def testNodeIdPresentIfColocated(): Unit = {
    val raftManager = instantiateRaftManagerWithConfigs("controller,broker", "1")
    assertTrue(raftManager.client.nodeId.getAsInt == 1)
    raftManager.shutdown()
  }

  @Test
  def testShutdownIoThread(): Unit = {
    val raftClient = mock(classOf[KafkaRaftClient[String]])
    val ioThread = new RaftIoThread(raftClient, threadNamePrefix = "test-raft")

    when(raftClient.isRunning).thenReturn(true)
    assertTrue(ioThread.isRunning)

    val shutdownFuture = new CompletableFuture[Void]
    when(raftClient.shutdown(5000)).thenReturn(shutdownFuture)

    ioThread.initiateShutdown()
    assertTrue(ioThread.isRunning)
    assertTrue(ioThread.isShutdownInitiated)
    verify(raftClient).shutdown(5000)

    shutdownFuture.complete(null)
    when(raftClient.isRunning).thenReturn(false)
    ioThread.run()
    assertFalse(ioThread.isRunning)
    assertTrue(ioThread.isShutdownComplete)
  }

  @Test
  def testUncaughtExceptionInIoThread(): Unit = {
    val raftClient = mock(classOf[KafkaRaftClient[String]])
    val ioThread = new RaftIoThread(raftClient, threadNamePrefix = "test-raft")

    when(raftClient.isRunning).thenReturn(true)
    assertTrue(ioThread.isRunning)

    when(raftClient.poll()).thenThrow(new RuntimeException)
    ioThread.run()

    assertTrue(ioThread.isShutdownComplete)
    assertTrue(ioThread.isThreadFailed)
    assertFalse(ioThread.isRunning)
  }

}
