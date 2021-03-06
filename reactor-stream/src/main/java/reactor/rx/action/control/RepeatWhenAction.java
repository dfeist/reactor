/*
 * Copyright (c) 2011-2015 Pivotal Software Inc., Inc. All Rights Reserved.
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
package reactor.rx.action.control;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.Publishers;
import reactor.core.support.BackpressureUtils;
import reactor.core.support.Bounded;
import reactor.fn.Function;
import reactor.fn.timer.Timer;
import reactor.rx.Stream;
import reactor.rx.action.Action;
import reactor.rx.broadcast.Broadcaster;

/**
 * @author Stephane Maldini
 * @since 2.0
 */
public class RepeatWhenAction<T> extends Action<T, T> {

	private final Broadcaster<Long>      retryStream;
	private final Publisher<? extends T> rootPublisher;
	private long pendingRequests = 0l;

	public RepeatWhenAction(Timer timer, Function<? super Stream<? extends Long>, ? extends Publisher<?>> predicate,
	                        Publisher<? extends T> rootPublisher) {
		this.retryStream = Broadcaster.create(timer);
		this.rootPublisher = rootPublisher != null ? Publishers.trampoline(rootPublisher) : null;
		Publisher<?> afterRetryPublisher = predicate.apply(retryStream);
		afterRetryPublisher.subscribe(new RestartSubscriber());
	}

	@Override
	protected void doNext(T ev) {
		broadcastNext(ev);
	}

	@Override
	protected void doComplete() {
		retryStream.onComplete();
		super.doComplete();
	}

	@Override
	public void requestMore(long n) {
		synchronized (this) {
			pendingRequests = BackpressureUtils.addOrLongMax(pendingRequests, n);
		}
		super.requestMore(n);
	}

	@Override
	protected void doOnSubscribe(Subscription subscription) {
		long pendingRequests = this.pendingRequests;
		if (pendingRequests > 0) {
			subscription.request(pendingRequests);
		}
	}


	protected void doRetry() {
		rootPublisher.subscribe(RepeatWhenAction.this);
	}

	@Override
	public void onComplete() {
		try {
			cancel();
			retryStream.onNext(System.currentTimeMillis());
		} catch (Exception e) {
			doError(e);
		}
	}

	private class RestartSubscriber implements Subscriber<Object>, Bounded {
		Subscription s;

		@Override
		public boolean isExposedToOverflow(Bounded upstream) {
			return RepeatWhenAction.this.isExposedToOverflow(upstream);
		}

		@Override
		public long getCapacity() {
			return capacity;
		}

		@Override
		public void onSubscribe(Subscription s) {
			this.s = s;
			s.request(1l);
		}

		@Override
		public void onNext(Object o) {
			//s.cancel();
			//publisher.subscribe(this);
			doRetry();
			s.request(1l);
		}

		@Override
		public void onError(Throwable t) {
			s.cancel();
			RepeatWhenAction.this.doError(t);
		}

		@Override
		public void onComplete() {
			s.cancel();
			RepeatWhenAction.this.doComplete();
		}
	}
}
