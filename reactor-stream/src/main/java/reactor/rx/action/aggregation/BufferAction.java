/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package reactor.rx.action.aggregation;

import reactor.fn.timer.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Stephane Maldini
 * @since 1.1
 */
public class BufferAction<T> extends BatchAction<T, List<T>> {

	private final List<T> values = new ArrayList<T>();

	public BufferAction(int batchsize) {
		super(batchsize, true, false, true);
	}

	public BufferAction(int maxSize, long timespan, TimeUnit unit, Timer timer) {
		super(maxSize, true, false, true, timespan, unit, timer);
	}

	@Override
	protected void doError(Throwable ev) {
		if (timer != null) {
			synchronized (timer) {
				values.clear();
			}
		} else {
			values.clear();
		}
		super.doError(ev);
	}

	@Override
	public void nextCallback(T value) {
		if (timer != null) {
			synchronized (timer) {
				values.add(value);
			}
		} else {
			values.add(value);
		}
	}

	@Override
	public void flushCallback(T ev) {
		final List<T> toSend;
		if (timer != null) {
			synchronized (timer) {
				if (values.isEmpty()) {
					return;
				}
				toSend = new ArrayList<T>(values);
				values.clear();
			}
		} else {
			if (values.isEmpty()) {
				return;
			}
			toSend = new ArrayList<T>(values);
			values.clear();
		}
		broadcastNext(toSend);
	}
}
