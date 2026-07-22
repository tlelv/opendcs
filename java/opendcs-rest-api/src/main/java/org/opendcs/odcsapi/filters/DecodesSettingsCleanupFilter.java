package org.opendcs.odcsapi.filters;

import java.io.IOException;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import decodes.util.DecodesSettings;

/**
 * Clears the per-thread {@link DecodesSettings} override set by
 * {@link org.opendcs.odcsapi.dao.OpenDcsDatabaseFactory#createDb} once a request finishes.
 * Request-handling threads are reused across requests (and organizations), so without this
 * the override for one organization's request would leak into whatever request that thread
 * services next.
 */
@Provider
@Priority(0)
public final class DecodesSettingsCleanupFilter implements ContainerResponseFilter
{
	@Override
	public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
			throws IOException
	{
		DecodesSettings.clearThreadInstance();
	}
}
