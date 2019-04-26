/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.CloseHelper;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(Theories.class)
public class UntetheredSubscriptionTest
{
    @DataPoint
    public static final String IPC_CHANNEL = "aeron:ipc?term-length=64k";

    @DataPoint
    public static final String UNICAST_CHANNEL = "aeron:udp?endpoint=localhost:54325|term-length=64k";

    @DataPoint
    public static final String SPY_CHANNEL = "aeron-spy:aeron:udp?endpoint=localhost:54325|term-length=64k";

    private static final int STREAM_ID = 1;
    private static final int FRAGMENT_COUNT_LIMIT = 10;
    private static final int MESSAGE_LENGTH = 512 - DataHeaderFlyweight.HEADER_LENGTH;

    private final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
        .errorHandler(Throwable::printStackTrace)
        .spiesSimulateConnection(true)
        .timerIntervalNs(TimeUnit.MILLISECONDS.toNanos(50))
        .untetheredWindowLimitTimeoutNs(TimeUnit.MILLISECONDS.toNanos(250))
        .threadingMode(ThreadingMode.SHARED));

    private final Aeron aeron = Aeron.connect(new Aeron.Context()
        .errorHandler(Throwable::printStackTrace));

    @After
    public void after()
    {
        CloseHelper.close(aeron);
        CloseHelper.close(driver);
        driver.context().deleteAeronDirectory();
    }

    @Theory
    @Test(timeout = 10_000)
    public void shouldBecomeUnavailableWhenNotKeepingUp(final String channel)
    {
        final MutableInteger fragmentCountOne = new MutableInteger();
        final FragmentHandler fragmentHandlerOne = (buffer, offset, length, header) -> fragmentCountOne.value++;
        final MutableInteger fragmentCountTwo = new MutableInteger();
        final FragmentHandler fragmentHandlerTwo = (buffer, offset, length, header) -> fragmentCountTwo.value++;
        final AtomicBoolean unavailableCalled = new AtomicBoolean();
        final UnavailableImageHandler handler = (image) -> unavailableCalled.set(true);

        final UnsafeBuffer srcBuffer = new UnsafeBuffer(ByteBuffer.allocate(MESSAGE_LENGTH));
        srcBuffer.setMemory(0, MESSAGE_LENGTH, (byte)'A');
        final String untetheredChannel = channel + "|tether=false";
        final String publicationChannel = channel.startsWith("aeron-spy:") ? channel.substring(10) : channel;
        boolean pollingUntethered = true;

        try (Subscription tetheredSub = aeron.addSubscription(channel, STREAM_ID);
            Subscription untetheredSub = aeron.addSubscription(untetheredChannel, STREAM_ID, null, handler);
            Publication publication = aeron.addPublication(publicationChannel, STREAM_ID))
        {
            while (!tetheredSub.isConnected() || !untetheredSub.isConnected())
            {
                SystemTest.checkInterruptedStatus();
                Thread.yield();
            }

            while (true)
            {
                if (publication.offer(srcBuffer) < 0)
                {
                    SystemTest.checkInterruptedStatus();
                    Thread.yield();
                }

                if (pollingUntethered && untetheredSub.poll(fragmentHandlerTwo, FRAGMENT_COUNT_LIMIT) > 0)
                {
                    pollingUntethered = false;
                }

                tetheredSub.poll(fragmentHandlerOne, FRAGMENT_COUNT_LIMIT);

                if (unavailableCalled.get())
                {
                    assertTrue(tetheredSub.isConnected());
                    assertFalse(untetheredSub.isConnected());

                    if (publication.offer(srcBuffer) < 0)
                    {
                        SystemTest.checkInterruptedStatus();
                        Thread.yield();
                    }

                    return;
                }
            }
        }
    }
}
