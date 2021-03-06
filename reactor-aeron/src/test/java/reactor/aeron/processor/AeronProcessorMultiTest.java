/*
 * Copyright (c) 2011-2015 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.aeron.processor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.io.buffer.Buffer;
import reactor.io.net.tcp.support.SocketUtils;
import reactor.rx.Streams;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * @author Anatoly Kadyshev
 */
public class AeronProcessorMultiTest {

	private static final int TIMEOUT_SECS = 5;

	private String CHANNEL = "udp://localhost:" + SocketUtils.findAvailableUdpPort();

	private int STREAM_ID = 10;

	private ThreadSnapshot threadSnapshot;

    @Before
	public void doSetup() {
		threadSnapshot = new ThreadSnapshot().take();

		AeronTestUtils.setAeronEnvProps();
	}

	@After
	public void doTeardown() throws InterruptedException {
		assertTrue(threadSnapshot.takeAndCompare(new String[] {"hash"}, TimeUnit.SECONDS.toMillis(TIMEOUT_SECS)));
	}

	@Test
	public void testErrorSentByOtherProcessorShutdownsMine() throws InterruptedException {
		AeronProcessor myProcessor = createProcessor("myProcessor");
		TestSubscriber mySubscriber = createTestSubscriber(3);
		myProcessor.subscribe(mySubscriber);

		AeronProcessor otherProcessor = createProcessor("otherProcessor");
		TestSubscriber otherSubscriber = createTestSubscriber(3);
		otherProcessor.subscribe(otherSubscriber);

		otherProcessor.onError(new RuntimeException("Bah"));

		mySubscriber.assertErrorReceived();
		otherSubscriber.assertErrorReceived();

        TestUtils.waitForTrue(TIMEOUT_SECS, "One of the processors is still alive",
                () -> !myProcessor.alive() && !otherProcessor.alive());
	}

	@Test
	public void testCompleteSendByOtherProcessorDoesNotShutdownMine() throws InterruptedException {
		AeronProcessor myProcessor = createProcessor("myProcessor");
		TestSubscriber mySubscriber = createTestSubscriber(3);
		myProcessor.subscribe(mySubscriber);

		AeronProcessor otherProcessor = createProcessor("otherProcessor");
		TestSubscriber otherSubscriber = createTestSubscriber(3);
		otherProcessor.subscribe(otherSubscriber);

		otherProcessor.onComplete();

		mySubscriber.assertNoCompleteReceived();
		otherSubscriber.assertNoCompleteReceived();

		myProcessor.onComplete();

		mySubscriber.assertCompleteReceived();
		otherSubscriber.assertCompleteReceived();
	}

	@Test
	public void testPublisherReceivesNextSignals() throws InterruptedException {
		AeronProcessor myProcessor = createProcessor("myProcessor");
		Streams.just(
				Buffer.wrap("Live"))
				.subscribe(myProcessor);

		AeronProcessor otherProcessor = createProcessor("otherProcessor");
		otherProcessor.onNext(Buffer.wrap("Glory"));

		TestSubscriber subscriber = createTestSubscriber(2);
		myProcessor.subscribe(subscriber);

		subscriber.assertAllEventsReceived();
		subscriber.assertNoCompleteReceived();

		otherProcessor.onComplete();
		subscriber.assertCompleteReceived();
	}

    @Test
    public void testSingleSenderTwoReceivers() throws InterruptedException {
        AeronProcessor server = createProcessor("server");
        Streams.just(
                Buffer.wrap("One"),
                Buffer.wrap("Two"),
                Buffer.wrap("Three"))
                .subscribe(server);

        AeronProcessor client1 = createProcessor("client-1");
        StepByStepTestSubscriber subscriber1 = new StepByStepTestSubscriber(TIMEOUT_SECS);
        client1.subscribe(subscriber1);

        AeronProcessor client2 = createProcessor("client-2");
        StepByStepTestSubscriber subscriber2 = new StepByStepTestSubscriber(TIMEOUT_SECS);
        client2.subscribe(subscriber2);

        subscriber1.request(1);
        subscriber1.assertNextSignals("One");
        subscriber2.assertNumNextSignalsReceived(0);

        subscriber1.request(1);
        subscriber1.assertNextSignals("One", "Two");
        subscriber2.assertNumNextSignalsReceived(0);

        subscriber2.request(1);
        subscriber1.assertNextSignals("One", "Two");
        subscriber2.assertNextSignals("One");

        subscriber1.request(1);
        subscriber2.request(2);
        subscriber1.assertNextSignals("One", "Two", "Three");
        subscriber2.assertNextSignals("One", "Two", "Three");

        client1.onComplete();
        client2.onComplete();

        subscriber1.assertCompleteReceived();
        subscriber2.assertCompleteReceived();
    }

    @Test
    public void testSingleSenderSendsErrorToTwoReceivers() throws InterruptedException {
        AeronProcessor server = createProcessor("server");
        Streams.concat(
                Streams.just(Buffer.wrap("One"), Buffer.wrap("Two")),
                Streams.fail(new RuntimeException("Something went wrong")))
                .subscribe(server);

        AeronProcessor client1 = createProcessor("client-1");
        StepByStepTestSubscriber subscriber1 = new StepByStepTestSubscriber(TIMEOUT_SECS);
        client1.subscribe(subscriber1);

        AeronProcessor client2 = createProcessor("client-2");
        StepByStepTestSubscriber subscriber2 = new StepByStepTestSubscriber(TIMEOUT_SECS);
        client2.subscribe(subscriber2);

        subscriber1.request(1);
        subscriber1.assertNextSignals("One");
        subscriber2.assertNumNextSignalsReceived(0);

        subscriber1.request(1);
        subscriber1.assertNextSignals("One", "Two");
        subscriber2.assertNumNextSignalsReceived(0);

        subscriber1.request(1);
        subscriber1.assertErrorReceived();
        subscriber2.assertErrorReceived();
    }

	private AeronProcessor createProcessor(String name) {
		return AeronProcessor.builder()
				.name(name)
				.autoCancel(false)
				.launchEmbeddedMediaDriver(true)
				.channel(CHANNEL)
				.streamId(STREAM_ID)
				.errorStreamId(STREAM_ID + 1)
				.commandRequestStreamId(STREAM_ID + 2)
				.commandReplyStreamId(STREAM_ID + 3)
				.publicationLingerTimeoutMillis(250)
				.share();
	}

	private TestSubscriber createTestSubscriber(int nExpectedEvents) {
		return new TestSubscriber(TIMEOUT_SECS, nExpectedEvents);
	}

}
