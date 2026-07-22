package decodes.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression test for the multi-tenant REST API race described in issue #2052: without a
 * per-thread override, concurrent requests for different organizations could observe each
 * other's {@link DecodesSettings}, e.g. one organization's platform list rendering with
 * another organization's siteNameTypePreference.
 */
public class DecodesSettingsThreadInstanceTest
{
	@AfterEach
	public void clearOverride()
	{
		DecodesSettings.clearThreadInstance();
	}

	@Test
	public void instanceFallsBackToGlobalSingletonWhenNoOverrideSet()
	{
		assertSame(DecodesSettings.instance(), DecodesSettings.instance());
	}

	@Test
	public void threadInstanceOverridesGlobalSingletonOnlyForCurrentThread()
	{
		DecodesSettings orgSettings = new DecodesSettings();
		orgSettings.siteNameTypePreference = "cwms";
		DecodesSettings.setThreadInstance(orgSettings);
		try
		{
			assertSame(orgSettings, DecodesSettings.instance());
		}
		finally
		{
			DecodesSettings.clearThreadInstance();
		}
		// once cleared, the thread falls back to the global singleton again
		assertSame(DecodesSettings.instance(), DecodesSettings.instance());
	}

	/**
	 * Simulates two "requests" for two different organizations running concurrently on two
	 * threads. Each sets its own DecodesSettings via setThreadInstance (as
	 * OpenDcsDatabaseFactory.createDb does per-request) and repeatedly reads
	 * DecodesSettings.instance().siteNameTypePreference while the other thread is doing the
	 * same with a different value. Before the thread-local override was introduced, both
	 * threads shared the single static instance, so this would intermittently observe the
	 * other organization's preference.
	 */
	@Test
	public void concurrentOrganizationsDoNotObserveEachOthersSettings() throws Exception
	{
		final int iterations = 2000;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try
		{
			CountDownLatch start = new CountDownLatch(1);
			AtomicReference<String> firstMismatch = new AtomicReference<>();

			Future<?> orgA = pool.submit(
					() -> raceLoop(start, "cwms", iterations, firstMismatch));
			Future<?> orgB = pool.submit(
					() -> raceLoop(start, "nwshb5", iterations, firstMismatch));

			start.countDown();
			orgA.get(30, TimeUnit.SECONDS);
			orgB.get(30, TimeUnit.SECONDS);

			assertEquals(null, firstMismatch.get(),
					"A thread observed a different organization's siteNameTypePreference");
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	private void raceLoop(CountDownLatch start, String expectedPreference, int iterations,
			AtomicReference<String> firstMismatch)
	{
		try
		{
			start.await();
			DecodesSettings settings = new DecodesSettings();
			settings.siteNameTypePreference = expectedPreference;
			for (int i = 0; i < iterations; i++)
			{
				DecodesSettings.setThreadInstance(settings);
				String observed = DecodesSettings.instance().siteNameTypePreference;
				if (!expectedPreference.equals(observed))
				{
					firstMismatch.compareAndSet(null,
							"expected " + expectedPreference + " but observed " + observed);
				}
			}
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
		}
		finally
		{
			DecodesSettings.clearThreadInstance();
		}
	}
}
