/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.javadsl;

import akka.Done;
import akka.NotUsed;
import akka.actor.Cancellable;

// #init-mat
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;

// #init-mat

// #publish-single
import akka.stream.alpakka.googlecloud.pubsub.grpc.javadsl.GooglePubSub;
import akka.stream.javadsl.*;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.*;

// #publish-single

import akka.stream.Materializer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class IntegrationTest {

  // #init-mat
  static final ActorSystem system = ActorSystem.create("IntegrationTest");
  static final Materializer materializer = ActorMaterializer.create(system);
  // #init-mat

  @Test
  public void shouldPublishAMessage()
      throws InterruptedException, ExecutionException, TimeoutException {
    // #publish-single
    final String projectId = "alpakka";
    final String topic = "simpleTopic";

    final PubsubMessage publishMessage =
        PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("Hello world!")).build();

    final PublishRequest publishRequest =
        PublishRequest.newBuilder()
            .setTopic("projects/" + projectId + "/topics/" + topic)
            .addMessages(publishMessage)
            .build();

    final Source<PublishRequest, NotUsed> source = Source.single(publishRequest);

    final Flow<PublishRequest, PublishResponse, NotUsed> publishFlow =
        GooglePubSub.publish(1, system);

    final CompletionStage<List<PublishResponse>> publishedMessageIds =
        source.via(publishFlow).runWith(Sink.seq(), materializer);
    // #publish-single

    assertTrue(
        "number of published messages should be more than 0",
        publishedMessageIds.toCompletableFuture().get(2, TimeUnit.SECONDS).size() > 0);
  }

  @Test
  public void shouldPublishBatch()
      throws InterruptedException, ExecutionException, TimeoutException {
    // #publish-fast
    final String projectId = "alpakka";
    final String topic = "simpleTopic";

    final PubsubMessage publishMessage =
        PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("Hello world!")).build();

    final Source<PubsubMessage, NotUsed> messageSource = Source.single(publishMessage);
    final CompletionStage<List<PublishResponse>> published =
        messageSource
            .groupedWithin(1000, Duration.ofMinutes(1))
            .map(
                messages ->
                    PublishRequest.newBuilder()
                        .setTopic("projects/" + projectId + "/topics/" + topic)
                        .addAllMessages(messages)
                        .build())
            .via(GooglePubSub.publish(1, system))
            .runWith(Sink.seq(), materializer);
    // #publish-fast

    assertTrue(
        "number of published messages should be more than 0",
        published.toCompletableFuture().get(2, TimeUnit.SECONDS).size() > 0);
  }

  @Test
  public void shouldSubscribe() throws InterruptedException, ExecutionException, TimeoutException {
    // #subscribe
    final String projectId = "alpakka";
    final String subscription = "simpleSubscription";

    final StreamingPullRequest request =
        StreamingPullRequest.newBuilder()
            .setSubscription("projects/" + projectId + "/subscriptions/" + subscription)
            .setStreamAckDeadlineSeconds(10)
            .build();

    final Duration pollInterval = Duration.ofSeconds(1);
    final Source<ReceivedMessage, CompletableFuture<Cancellable>> subscriptionSource =
        GooglePubSub.subscribe(request, pollInterval, system);
    // #subscribe

    final CompletionStage<ReceivedMessage> first =
        subscriptionSource.runWith(Sink.head(), materializer);

    final String topic = "simpleTopic";
    final ByteString msg = ByteString.copyFromUtf8("Hello world!");

    final PubsubMessage publishMessage = PubsubMessage.newBuilder().setData(msg).build();

    final PublishRequest publishRequest =
        PublishRequest.newBuilder()
            .setTopic("projects/" + projectId + "/topics/" + topic)
            .addMessages(publishMessage)
            .build();

    Source.single(publishRequest)
        .via(GooglePubSub.publish(1, system))
        .runWith(Sink.ignore(), materializer);

    assertEquals(
        "received and expected messages not the same",
        msg,
        first.toCompletableFuture().get(2, TimeUnit.SECONDS).getMessage().getData());
  }

  @Test
  public void shouldAcknowledge() {
    final String projectId = "alpakka";
    final String subscription = "simpleSubscription";

    final StreamingPullRequest request =
        StreamingPullRequest.newBuilder()
            .setSubscription("projects/" + projectId + "/subscriptions/" + subscription)
            .setStreamAckDeadlineSeconds(10)
            .build();

    final Duration pollInterval = Duration.ofSeconds(1);
    final Source<ReceivedMessage, CompletableFuture<Cancellable>> subscriptionSource =
        GooglePubSub.subscribe(request, pollInterval, system);

    // #acknowledge
    final Sink<AcknowledgeRequest, CompletionStage<Done>> ackSink =
        GooglePubSub.acknowledge(1, system);

    subscriptionSource
        .map(
            receivedMessage -> {
              // do some computation
              return receivedMessage.getAckId();
            })
        .groupedWithin(10, Duration.ofSeconds(1))
        .map(acks -> AcknowledgeRequest.newBuilder().addAllAckIds(acks).build())
        .to(ackSink);
    // #acknowledge
  }

  @AfterClass
  public static void tearDown() {
    system.terminate();
  }
}