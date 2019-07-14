package io.scalecube.services.discovery;

import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.ENDPOINT_ADDED;
import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.ENDPOINT_ADDED_TO_GROUP;
import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.ENDPOINT_REMOVED;
import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.ENDPOINT_REMOVED_FROM_GROUP;
import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.GROUP_ADDED;
import static io.scalecube.services.discovery.api.ServiceDiscoveryEvent.Type.GROUP_REMOVED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isOneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.scalecube.cluster.gossip.GossipConfig;
import io.scalecube.net.Address;
import io.scalecube.services.ServiceEndpoint;
import io.scalecube.services.discovery.api.ServiceDiscovery;
import io.scalecube.services.discovery.api.ServiceDiscoveryEvent;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.test.StepVerifier;

class ScalecubeServiceDiscoveryTest extends BaseTest {

  public static final Duration TIMEOUT = Duration.ofSeconds(5);
  public static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);

  @BeforeAll
  public static void setUp() {
    StepVerifier.setDefaultTimeout(TIMEOUT);
  }

  @Test
  public void testEndpointsInGroupRestarted(TestInfo testInfo) {
    String groupId = Integer.toHexString(testInfo.getDisplayName().hashCode());

    Address seedAddress = startSeed();

    int groupSize = 3;

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r3 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));

    Stream.of(r1.groupDiscoveryEvents(), r2.groupDiscoveryEvents(), r3.groupDiscoveryEvents())
        .forEach(
            rp ->
                StepVerifier.create(rp)
                    .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
                    .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
                    .assertNext(
                        event -> {
                          assertEquals(GROUP_ADDED, event.type());
                          assertEquals(groupSize, event.groupSize());
                        })
                    .expectNoEvent(SHORT_TIMEOUT)
                    .thenCancel()
                    .verify());

    System.out.println("\n\n\n\n\n\n");
    Mono.delay(SHORT_TIMEOUT).repeat(2).subscribe();

    // shutdown r2 and r3, verify events on r1
    r1 = r1.recreate();
    StepVerifier.create(Flux.concat(r1.groupDiscoveryEvents()))
        .then(r2::shutdown)
        .then(r3::shutdown)
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED_FROM_GROUP, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED_FROM_GROUP, event.type()))
        .expectNoEvent(SHORT_TIMEOUT)
        .thenCancel()
        .verify();
  }

  @Test
  public void testEndpointIsAddedThenRemoved() {
    Address seedAddress = startSeed();

    AtomicInteger registeredCount = new AtomicInteger();
    AtomicInteger unregisteredCount = new AtomicInteger();

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(() -> newServiceDiscovery(seedAddress));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(() -> newServiceDiscovery(seedAddress));
    RecordingServiceDiscovery r3 =
        RecordingServiceDiscovery.create(() -> newServiceDiscovery(seedAddress));

    int expectedAddedEventsNum = 9; // (1+3)x(1+3) - (1+3)/*exclude self*/ - 3/*exclude seed*/
    int expectedRemovedEventsNum = 2; // r3 is shutdown => await by 1 event on r1 and r2

    StepVerifier.create(
            Flux.merge(r1.discoveryEvents(), r2.discoveryEvents(), r3.discoveryEvents()))
        .thenConsumeWhile(
            event -> {
              assertEquals(ENDPOINT_ADDED, event.type());
              assertNotNull(event.serviceEndpoint());
              return registeredCount.incrementAndGet() < expectedAddedEventsNum;
            })
        .expectNoEvent(SHORT_TIMEOUT)
        .then(r3::shutdown)
        .thenConsumeWhile(
            event -> {
              assertEquals(ENDPOINT_REMOVED, event.type());
              assertNotNull(event.serviceEndpoint());
              return unregisteredCount.incrementAndGet() < expectedRemovedEventsNum;
            })
        .expectNoEvent(SHORT_TIMEOUT)
        .thenCancel()
        .verify();
  }

  @Test
  public void testEndpointsRestarted() {
    Address seedAddress = startSeed();

    AtomicInteger registeredCount = new AtomicInteger();

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(() -> newServiceDiscovery(seedAddress));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(() -> newServiceDiscovery(seedAddress));
    RecordingServiceDiscovery r3 =
        RecordingServiceDiscovery.create(() -> newServiceDiscovery(seedAddress));

    int expectedAddedEventsNum = 9; // (1+3)x(1+3) - (1+3)/*exclude self*/ - 3/*exclude seed*/

    StepVerifier.create(
            Flux.merge(r1.discoveryEvents(), r2.discoveryEvents(), r3.discoveryEvents()))
        .thenConsumeWhile(
            event -> {
              assertEquals(ENDPOINT_ADDED, event.type());
              assertNotNull(event.serviceEndpoint());
              return registeredCount.incrementAndGet() < expectedAddedEventsNum;
            })
        .expectNoEvent(TIMEOUT)
        .thenCancel()
        .verify();

    // restart discovery instances
    AtomicInteger registeredCountAfterRestart = new AtomicInteger();
    AtomicInteger removedCountAfterRestart = new AtomicInteger();
    int expectedRemovedEventsNum = 3; // r1,r2,r3 are shutdown => await 3 events

    r1 = r1.shutdown().recreate();
    r2 = r2.shutdown().recreate();
    r3 = r3.shutdown().recreate();

    StepVerifier.create(
            Flux.merge(r1.discoveryEvents(), r2.discoveryEvents(), r3.discoveryEvents()))
        .thenConsumeWhile(
            event -> {
              assertThat(event.type(), isOneOf(ENDPOINT_ADDED, ENDPOINT_REMOVED));
              if (event.type() == ENDPOINT_ADDED) {
                registeredCountAfterRestart.incrementAndGet();
              }
              if (event.type() == ENDPOINT_REMOVED) {
                removedCountAfterRestart.incrementAndGet();
              }
              return registeredCountAfterRestart.get() + removedCountAfterRestart.get() + 3 /*seed*/
                  < expectedAddedEventsNum + expectedRemovedEventsNum + 3 /*seed*/;
            })
        .expectNoEvent(SHORT_TIMEOUT)
        .thenCancel()
        .verify();
  }

  @Test
  public void testGroupIsAdded(TestInfo testInfo) {
    String groupId = Integer.toHexString(testInfo.getDisplayName().hashCode());

    Address seedAddress = startSeed();

    int groupSize = 3;

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r3 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));

    Stream.of(r1.groupDiscoveryEvents(), r2.groupDiscoveryEvents(), r3.groupDiscoveryEvents())
        .forEach(
            rp ->
                StepVerifier.create(rp)
                    .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
                    .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
                    .assertNext(
                        event -> {
                          assertEquals(GROUP_ADDED, event.type());
                          assertEquals(groupSize, event.groupSize());
                        })
                    .expectNoEvent(SHORT_TIMEOUT)
                    .thenCancel()
                    .verify());
  }

  @Test
  public void testSameGroupIdDifferentGroupSizes(TestInfo testInfo) {
    String groupId = Integer.toHexString(testInfo.getDisplayName().hashCode());

    Address seedAddress = startSeed();

    int groupSize_1 = 1;
    int groupSize_2 = 2;

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize_1));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize_2));
    RecordingServiceDiscovery r3 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize_2));

    // Verify that group with groupSize_1 has been built
    Stream.of(r1.groupDiscoveryEvents())
        .forEach(
            rp ->
                StepVerifier.create(rp)
                    .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
                    .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
                    .assertNext(
                        event -> {
                          assertEquals(GROUP_ADDED, event.type());
                          assertEquals(groupSize_2, event.groupSize());
                        })
                    .expectNoEvent(SHORT_TIMEOUT)
                    .thenCancel()
                    .verify());

    // Verify that group with groupSize_2 has been built as well
    Stream.of(r2.groupDiscoveryEvents(), r3.groupDiscoveryEvents())
        .forEach(
            rp ->
                StepVerifier.create(rp)
                    .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
                    .assertNext(
                        event -> {
                          assertEquals(GROUP_ADDED, event.type());
                          assertThat(event.groupSize(), isOneOf(groupSize_1, groupSize_2));
                        })
                    .expectNoEvent(SHORT_TIMEOUT)
                    .thenCancel()
                    .verify());
  }

  @Test
  public void testSameGroupIdSameGroupSizeWithSeveralInstances(TestInfo testInfo) {
    String groupId = Integer.toHexString(testInfo.getDisplayName().hashCode());

    Address seedAddress = startSeed();

    int groupSize = 1;

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r3 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));

    // Verify that only one group with groupSize and groupId combination has been built
    Stream.of(r1.groupDiscoveryEvents(), r2.groupDiscoveryEvents(), r3.groupDiscoveryEvents())
        .forEach(
            rp ->
                StepVerifier.create(rp)
                    .expectSubscription()
                    .expectNoEvent(SHORT_TIMEOUT)
                    .thenCancel()
                    .verify());
  }

  @Disabled("https://github.com/scalecube/scalecube-services/issues/599")
  public void testSingleNodeGroupIsStillGroup(TestInfo testInfo) {
    String groupId = Integer.toHexString(testInfo.getDisplayName().hashCode());

    Address seedAddress = startSeed();

    int groupSize = 1; // group of size 1

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(() -> newServiceDiscovery(seedAddress));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));

    // Verify that group has been built and notified about that
    StepVerifier.create(Flux.merge(r1.groupDiscoveryEvents(), r2.groupDiscoveryEvents()))
        .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
        .assertNext(
            event -> {
              assertEquals(GROUP_ADDED, event.type());
              assertEquals(groupSize, event.groupSize());
            })
        .then(r2::shutdown)
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED_FROM_GROUP, event.type()))
        .assertNext(
            event -> {
              assertEquals(GROUP_REMOVED, event.type());
              assertEquals(0, event.groupSize());
            })
        .expectNoEvent(SHORT_TIMEOUT)
        .thenCancel()
        .verify();
  }

  @Test
  public void testGroupEventsOnBehalfOfNonGroupMember(TestInfo testInfo) {
    String groupId = Integer.toHexString(testInfo.getDisplayName().hashCode());

    Address seedAddress = startSeed();

    int groupSize = 2;

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));

    // Verify that Group under group id has been built
    Stream.of(r1.groupDiscoveryEvents(), r2.groupDiscoveryEvents())
        .forEach(
            rp ->
                StepVerifier.create(rp)
                    .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
                    .assertNext(
                        event -> {
                          assertEquals(GROUP_ADDED, event.type());
                          assertEquals(groupSize, event.groupSize());
                        })
                    .expectNoEvent(SHORT_TIMEOUT)
                    .thenCancel()
                    .verify());

    RecordingServiceDiscovery r3 =
        RecordingServiceDiscovery.create(() -> newServiceDiscovery(seedAddress));

    // Verify registered/unregistered group events on non-group member
    StepVerifier.create(r3.groupDiscoveryEvents())
        .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
        .assertNext(
            event -> {
              assertEquals(GROUP_ADDED, event.type());
              assertEquals(groupSize, event.groupSize());
            })
        .then(r1::shutdown)
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED_FROM_GROUP, event.type()))
        .then(r2::shutdown)
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED_FROM_GROUP, event.type()))
        .assertNext(
            event -> {
              assertEquals(GROUP_REMOVED, event.type());
              assertEquals(0, event.groupSize());
            })
        .expectNoEvent(SHORT_TIMEOUT)
        .thenCancel()
        .verify();
  }

  @Test
  public void testGroupIsNotRemovedAsLongAsOneIsLeft(TestInfo testInfo) {
    String groupId = Integer.toHexString(testInfo.getDisplayName().hashCode());

    Address seedAddress = startSeed();

    int groupSize = 3;

    RecordingServiceDiscovery r1 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r2 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));
    RecordingServiceDiscovery r3 =
        RecordingServiceDiscovery.create(
            () -> newServiceGroupDiscovery(seedAddress, groupId, groupSize));

    StepVerifier.create(r1.groupDiscoveryEvents())
        .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
        .assertNext(event -> assertEquals(ENDPOINT_ADDED_TO_GROUP, event.type()))
        .assertNext(
            event -> {
              assertEquals(GROUP_ADDED, event.type());
              assertEquals(groupSize, event.groupSize());
            })
        .then(r2::shutdown)
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED_FROM_GROUP, event.type()))
        .then(r3::shutdown)
        .assertNext(event -> assertEquals(ENDPOINT_REMOVED_FROM_GROUP, event.type()))
        .expectNoEvent(SHORT_TIMEOUT)
        .thenCancel()
        .verify();
  }

  public static ServiceEndpoint newServiceEndpoint() {
    return ServiceEndpoint.builder().id(UUID.randomUUID().toString()).build();
  }

  public static ServiceEndpoint newServiceGroupEndpoint(String groupId, int groupSize) {
    return ServiceEndpoint.builder()
        .id(UUID.randomUUID().toString())
        .serviceGroup(groupId, groupSize)
        .build();
  }

  public Mono<ServiceDiscovery> newServiceGroupDiscovery(
      Address seedAddress, String groupId, int groupSize) {
    return Mono.fromCallable(
        () -> {
          ServiceEndpoint serviceEndpoint = newServiceGroupEndpoint(groupId, groupSize);
          return new ScalecubeServiceDiscovery(serviceEndpoint)
              .options(opts -> opts.gossip(cfg -> GossipConfig.defaultLocalConfig()))
              .options(opts -> opts.membership(cfg -> cfg.seedMembers(seedAddress)));
        });
  }

  private Mono<ServiceDiscovery> newServiceDiscovery(Address seedAddress) {
    return Mono.fromCallable(
        () -> {
          ServiceEndpoint serviceEndpoint = newServiceEndpoint();
          return new ScalecubeServiceDiscovery(serviceEndpoint)
              .options(opts -> opts.gossip(cfg -> GossipConfig.defaultLocalConfig()))
              .options(opts -> opts.membership(cfg -> cfg.seedMembers(seedAddress)));
        });
  }

  private Address startSeed() {
    return new ScalecubeServiceDiscovery(newServiceEndpoint())
        .options(opts -> opts.gossip(cfg -> GossipConfig.defaultLocalConfig()))
        .start()
        .block()
        .address();
  }

  private static class RecordingServiceDiscovery {

    ServiceDiscovery instance;
    Supplier<Mono<ServiceDiscovery>> supplier;
    ReplayProcessor<ServiceDiscoveryEvent> discoveryEvents = ReplayProcessor.create();

    Flux<ServiceDiscoveryEvent> discoveryEvents() {
      return discoveryEvents.filter(ScalecubeServiceDiscoveryTest::filterDiscoveryEvents);
    }

    Flux<ServiceDiscoveryEvent> groupDiscoveryEvents() {
      return discoveryEvents.filter(ScalecubeServiceDiscoveryTest::filterGroupDiscoveryEvents);
    }

    RecordingServiceDiscovery recreate() {
      return create(supplier);
    }

    static RecordingServiceDiscovery create(Supplier<Mono<ServiceDiscovery>> supplier) {
      RecordingServiceDiscovery result = new RecordingServiceDiscovery();
      result.supplier = supplier;
      supplier
          .get()
          .log("serviceDiscovery", Level.INFO)
          .subscribe(
              serviceDiscovery -> {
                result.instance = serviceDiscovery;
                serviceDiscovery
                    .listenDiscovery()
                    .log("listenDiscovery", Level.INFO)
                    .subscribe(result.discoveryEvents);
                serviceDiscovery.start().block();
              });
      return result;
    }

    RecordingServiceDiscovery shutdown() {
      instance.shutdown().block();
      return this;
    }
  }

  private static boolean filterDiscoveryEvents(ServiceDiscoveryEvent event) {
    return event.isEndpointAdded() || event.isEndpointRemoved();
  }

  private static boolean filterGroupDiscoveryEvents(ServiceDiscoveryEvent event) {
    return event.isEndpointAddedToTheGroup()
        || event.isEndpointRemovedFromTheGroup()
        || event.isGroupAdded()
        || event.isGroupRemoved();
  }
}
