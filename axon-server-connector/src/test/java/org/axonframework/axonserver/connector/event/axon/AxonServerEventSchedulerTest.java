/*
 *  Copyright (c) 2010-2020. Axon Framework
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package org.axonframework.axonserver.connector.event.axon;

import io.axoniq.axonserver.grpc.ErrorMessage;
import io.axoniq.axonserver.grpc.InstructionAck;
import io.axoniq.axonserver.grpc.control.ClientIdentification;
import io.axoniq.axonserver.grpc.control.PlatformInboundInstruction;
import io.axoniq.axonserver.grpc.control.PlatformInfo;
import io.axoniq.axonserver.grpc.control.PlatformOutboundInstruction;
import io.axoniq.axonserver.grpc.control.PlatformServiceGrpc;
import io.axoniq.axonserver.grpc.event.CancelScheduledEventRequest;
import io.axoniq.axonserver.grpc.event.Event;
import io.axoniq.axonserver.grpc.event.EventSchedulerGrpc;
import io.axoniq.axonserver.grpc.event.RescheduleEventRequest;
import io.axoniq.axonserver.grpc.event.ScheduleEventRequest;
import io.axoniq.axonserver.grpc.event.ScheduleToken;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventhandling.scheduling.java.SimpleScheduleToken;
import org.axonframework.eventhandling.scheduling.quartz.QuartzScheduleToken;
import org.axonframework.lifecycle.ShutdownInProgressException;
import org.axonframework.messaging.MetaData;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Marc Gathier
 */
public class AxonServerEventSchedulerTest {

    private static Server server;
    private static Map<String, Event> scheduled = new ConcurrentHashMap<>();
    private AxonServerEventScheduler testSubject;

    @BeforeAll
    public static void startServer() throws Exception {
        server = ServerBuilder.forPort(18024)
                              .addService(new PlatformServiceGrpc.PlatformServiceImplBase() {
                                  @Override
                                  public void getPlatformServer(ClientIdentification request,
                                                                StreamObserver<PlatformInfo> responseObserver) {
                                      responseObserver.onNext(PlatformInfo.newBuilder().setSameConnection(true)
                                                                          .build());
                                      responseObserver.onCompleted();
                                  }

                                  @Override
                                  public StreamObserver<PlatformInboundInstruction> openStream(
                                          StreamObserver<PlatformOutboundInstruction> responseObserver) {
                                      return new StreamObserver<PlatformInboundInstruction>() {
                                          @Override
                                          public void onNext(PlatformInboundInstruction platformInboundInstruction) {

                                          }

                                          @Override
                                          public void onError(Throwable throwable) {

                                          }

                                          @Override
                                          public void onCompleted() {

                                          }
                                      };
                                  }
                              })
                              .addService(new EventSchedulerGrpc.EventSchedulerImplBase() {
                                  @Override
                                  public void scheduleEvent(ScheduleEventRequest request,
                                                            StreamObserver<ScheduleToken> responseObserver) {
                                      String id = UUID.randomUUID().toString();
                                      scheduled.put(id, request.getEvent());
                                      responseObserver.onNext(ScheduleToken.newBuilder().setToken(id).build());
                                      responseObserver.onCompleted();
                                  }

                                  @Override
                                  public void rescheduleEvent(RescheduleEventRequest request,
                                                              StreamObserver<ScheduleToken> responseObserver) {
                                      String token = request.getToken();
                                      if (request.getToken().equals("")) {
                                          token = UUID.randomUUID().toString();
                                      }
                                      scheduled.put(token, request.getEvent());
                                      responseObserver.onNext(ScheduleToken.newBuilder()
                                                                           .setToken(token)
                                                                           .build());
                                      responseObserver.onCompleted();
                                  }

                                  @Override
                                  public void cancelScheduledEvent(CancelScheduledEventRequest request,
                                                                   StreamObserver<InstructionAck> responseObserver) {
                                      if (!scheduled.containsKey(request.getToken())) {
                                          responseObserver.onNext(InstructionAck.newBuilder()
                                                                                .setSuccess(false)
                                                                                .setError(ErrorMessage.newBuilder()
                                                                                                      .setMessage(
                                                                                                              "Schedule not found")
                                                                                                      .addDetails(
                                                                                                              "Detail1")
                                                                                                      .addDetails(
                                                                                                              "Detail2")
                                                                                                      .setErrorCode(
                                                                                                              "AXONIQ-2610")
                                                                                                      .build())
                                                                                .build());
                                      } else {
                                          scheduled.remove(request.getToken());
                                          responseObserver.onNext(InstructionAck.newBuilder().setSuccess(true).build());
                                      }
                                      responseObserver.onCompleted();
                                  }
                              }).build();
        server.start();
    }

    @AfterAll
    public static void shutdown() throws Exception {
        server.shutdownNow().awaitTermination();
    }

    @BeforeEach
    public void setUp() {
        AxonServerConfiguration axonserverConfiguration = AxonServerConfiguration.builder()
                                                                                 .servers("localhost:18024")
                                                                                 .build();
        testSubject = AxonServerEventScheduler.builder()
                                              .connectionManager(AxonServerConnectionManager.builder()
                                                                                            .axonServerConfiguration(
                                                                                                    axonserverConfiguration)
                                                                                            .build())
                                              .configuration(axonserverConfiguration)
                                              .build();
    }

    @Test
    public void schedule() {
        org.axonframework.eventhandling.scheduling.ScheduleToken token = testSubject.schedule(Instant.now()
                                                                                                     .plus(Duration.ofMinutes(
                                                                                                             5)),
                                                                                              "TestEvent");
        assertTrue(token instanceof SimpleScheduleToken);
        SimpleScheduleToken simpleScheduleToken = (SimpleScheduleToken) token;
        assertNotNull(scheduled.get(simpleScheduleToken.getTokenId()));
    }

    @Test
    public void scheduleWithDuration() {
        org.axonframework.eventhandling.scheduling.ScheduleToken token = testSubject.schedule(Duration.ofMinutes(5),
                                                                                              "TestEvent");
        assertTrue(token instanceof SimpleScheduleToken);
        SimpleScheduleToken simpleScheduleToken = (SimpleScheduleToken) token;
        assertNotNull(scheduled.get(simpleScheduleToken.getTokenId()));
    }

    @Test
    public void scheduleDuringShutdown() {
        testSubject.shutdownDispatching();
        assertThrows(ShutdownInProgressException.class, () -> testSubject.schedule(Duration.ofMinutes(5), "TestEvent"));
    }

    @Test
    public void cancelSchedule() {
        scheduled.put("12345", Event.newBuilder().build());
        testSubject.cancelSchedule(new SimpleScheduleToken("12345"));
    }

    @Test
    public void cancelWithQuartzScheduleToken() {
        assertThrows(IllegalArgumentException.class, () -> testSubject.cancelSchedule(new
                                                                                              QuartzScheduleToken("job",
                                                                                                                  "12345")));
    }

    @Test
    public void reschedule() {
        String token = "12345";
        scheduled.put(token, Event.newBuilder().build());
        testSubject.reschedule(new SimpleScheduleToken(token), Duration.ofDays(1), new GenericEventMessage<>("Updated",
                                                                                                             MetaData.with(
                                                                                                                     "updated",
                                                                                                                     "true")));

        assertNotNull(scheduled.get(token));
        assertEquals(1, scheduled.get(token).getMetaDataCount());
    }

    @Test
    public void rescheduleWithoutToken() {
        org.axonframework.eventhandling.scheduling.ScheduleToken token =
                testSubject.reschedule(null, Duration.ofDays(1),
                                       new GenericEventMessage<>("Updated", MetaData.with("updated", "true")));
        assertTrue(token instanceof SimpleScheduleToken);
        SimpleScheduleToken simpleScheduleToken = (SimpleScheduleToken) token;
        assertNotNull(scheduled.get(simpleScheduleToken.getTokenId()));
    }
}
