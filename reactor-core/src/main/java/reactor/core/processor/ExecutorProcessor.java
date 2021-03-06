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
package reactor.core.processor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscription;
import reactor.core.error.Exceptions;
import reactor.core.support.SignalType;
import reactor.core.support.SingleUseExecutor;

/**
 * A base processor used by executor backed processors to take care of their ExecutorService
 *
 * @author Stephane Maldini
 */
public abstract class ExecutorProcessor<IN, OUT> extends BaseProcessor<IN, OUT> {

	protected final ExecutorService executor;

	protected volatile boolean cancelled;
	protected volatile int     terminated;

	protected final static AtomicIntegerFieldUpdater<ExecutorProcessor> TERMINATED =
			AtomicIntegerFieldUpdater.newUpdater(ExecutorProcessor.class, "terminated");

	protected ExecutorProcessor(String name, ExecutorService executor,
			boolean autoCancel) {
		super(new ClassLoader(Thread.currentThread()
		                            .getContextClassLoader()) {
		}, autoCancel);
		if (executor == null) {
			this.executor = SingleUseExecutor.create(name, contextClassLoader);
		}
		else {
			this.executor = executor;
		}
	}

	@Override
	protected void doOnSubscribe(Subscription s) {
		if (s != SignalType.NOOP_SUBSCRIPTION) {
			requestTask(s);
		}
	}

	protected void requestTask(final Subscription s) {
		//implementation might run a specific request task for the given subscription
	}

	protected void doComplete() {

	}

	@Override
	public final void onComplete() {
		if (TERMINATED.compareAndSet(this, 0, 1)) {
			upstreamSubscription = null;
			if (executor.getClass() == SingleUseExecutor.class) {
				executor.shutdown();
			}
			doComplete();
		}
	}

	protected void doError(Throwable throwable) {

	}

	@Override
	public final void onError(Throwable t) {
		super.onError(t);
		if (TERMINATED.compareAndSet(this, 0, 1)) {
			upstreamSubscription = null;
			if (executor.getClass() == SingleUseExecutor.class) {
				executor.shutdown();
			}
			doError(t);
		}
	}


	/**
	 * Block until all submitted tasks have completed, then do a normal {@link #shutdown()}.
	 */
	public boolean awaitAndShutdown() {
		return awaitAndShutdown(-1, TimeUnit.SECONDS);
	}

	/**
	 * Block until all submitted tasks have completed, then do a normal {@link #shutdown()}.
	 */
	public boolean awaitAndShutdown(long timeout, TimeUnit timeUnit) {
		try {
			shutdown();
			return executor.awaitTermination(timeout, timeUnit);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	@Override
	protected void cancel(Subscription subscription) {
		cancelled = true;
		if(TERMINATED.compareAndSet(this, 0, 1)) {
			if (executor.getClass() == SingleUseExecutor.class) {
				executor.shutdown();
			}
		}
	}

	/**
	 * Shutdown this {@code Processor}, forcibly halting any work currently executing and discarding any tasks that
	 * have not yet been executed.
	 */
	public void forceShutdown() {
		if (executor.isShutdown()) return;
		executor.shutdownNow();
	}


	/**
	 * Determine whether this {@code Processor} can be used.
	 *
	 * @return {@literal true} if this {@code Resource} is alive and can be used, {@literal false} otherwise.
	 */
	public boolean alive() {
		return 0 == terminated;
	}

	/**
	 * Shutdown this active {@code Processor} such that it can no longer be used. If the resource carries any work,
	 * it will wait (but NOT blocking the caller) for all the remaining tasks to perform before closing the resource.
	 */
	public void shutdown() {
		try {
			onComplete();
			executor.shutdown();
		} catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			onError(t);
		}
	}

	/**
	 * @return true if the attached Subscribers will read exclusive sequences (akin to work-queue pattern)
	 */
	public abstract boolean isWork();

}
