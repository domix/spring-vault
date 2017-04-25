/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.authentication;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.util.Assert;
import org.springframework.util.NumberUtils;
import org.springframework.vault.VaultException;
import org.springframework.vault.client.VaultHttpHeaders;
import org.springframework.vault.client.VaultResponses;
import org.springframework.vault.support.VaultToken;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;

/**
 * Lifecycle-aware Session Manager. This {@link SessionManager} obtains tokens from a
 * {@link ClientAuthentication} upon {@link #getSessionToken() request}. Tokens are
 * renewed asynchronously if a token has a lease duration. This happens 5 seconds before
 * the token expires, see {@link #REFRESH_PERIOD_BEFORE_EXPIRY}.
 * <p>
 * This {@link SessionManager} also implements {@link DisposableBean} to revoke the
 * {@link LoginToken} once it's not required anymore. Token revocation will stop regular
 * token refresh.
 * <p>
 * If Token renewal runs into a client-side error, it assumes the token was
 * revoked/expired and discards the token state so the next attempt will lead to another
 * login attempt.
 *
 * @author Mark Paluch
 * @see LoginToken
 * @see SessionManager
 * @see AsyncTaskExecutor
 */
public class LifecycleAwareSessionManager implements SessionManager, DisposableBean {

	public static final int REFRESH_PERIOD_BEFORE_EXPIRY = 5;

	private final static Log logger = LogFactory
			.getLog(LifecycleAwareSessionManager.class);

	private final ClientAuthentication clientAuthentication;

	private final RestOperations restOperations;

	private final TaskScheduler taskScheduler;

	private final Object lock = new Object();

	private volatile Optional<VaultToken> token = Optional.empty();

	/**
	 * Create a {@link LifecycleAwareSessionManager} given {@link ClientAuthentication},
	 * {@link AsyncTaskExecutor} and {@link RestOperations}.
	 *
	 * @param clientAuthentication must not be {@literal null}.
	 * @param taskScheduler must not be {@literal null}.
	 * @param restOperations must not be {@literal null}.
	 */
	public LifecycleAwareSessionManager(ClientAuthentication clientAuthentication,
			TaskScheduler taskScheduler, RestOperations restOperations) {

		Assert.notNull(clientAuthentication, "ClientAuthentication must not be null");
		Assert.notNull(taskScheduler, "TaskScheduler must not be null");
		Assert.notNull(restOperations, "RestOperations must not be null");

		this.clientAuthentication = clientAuthentication;
		this.restOperations = restOperations;
		this.taskScheduler = taskScheduler;
	}

	@Override
	public void destroy() {

		Optional<VaultToken> token = this.token;
		this.token = Optional.empty();

		token.filter(LoginToken.class::isInstance).ifPresent(vaultToken -> {

			try {
				restOperations.postForObject("/auth/token/revoke-self",
						new HttpEntity<>(VaultHttpHeaders.from(vaultToken)), Map.class);
			}
			catch (HttpStatusCodeException e) {
				logger.warn(String.format("Cannot revoke VaultToken: %s",
						VaultResponses.getError(e.getResponseBodyAsString())));
			}
		});
	}

	/**
	 * Performs a token refresh. Create a new token if no token was obtained before. If a
	 * token was obtained before, it uses self-renewal to renew the current token.
	 * Client-side errors (like permission denied) indicate the token cannot be renewed
	 * because it's expired or simply not found.
	 *
	 * @return {@literal true} if the refresh was successful. {@literal false} if a new
	 * token was obtained or refresh failed.
	 */
	protected boolean renewToken() {

		logger.info("Renewing token");

		if (!token.isPresent()) {
			getSessionToken();
			return false;
		}

		try {
			restOperations.postForObject("/auth/token/renew-self",
					new HttpEntity<>(VaultHttpHeaders.from(token.get())), Map.class);
			return true;
		}
		catch (HttpStatusCodeException e) {

			if (e.getStatusCode().is4xxClientError()) {
				logger.debug(String.format(
						"Cannot refresh token, resetting token and performing re-login: %s",
						VaultResponses.getError(e.getResponseBodyAsString())));
				token = null;
				return false;
			}

			throw new VaultException(
					VaultResponses.getError(e.getResponseBodyAsString()));
		}
		catch (RestClientException e) {
			throw new VaultException("Cannot refresh token", e);
		}
	}

	@Override
	public VaultToken getSessionToken() {

		if (!token.isPresent()) {

			synchronized (lock) {

				if (!token.isPresent()) {
					token = Optional.ofNullable(clientAuthentication.login());

					if (isTokenRenewable()) {
						scheduleRenewal();
					}
				}
			}
		}

		return token
				.orElseThrow(() -> new IllegalStateException("Cannot obtain VaultToken"));
	}

	private boolean isTokenRenewable() {

		return token.filter(LoginToken.class::isInstance) //
				.filter(it -> {

					LoginToken loginToken = (LoginToken) it;
					return loginToken.getLeaseDuration() > 0 && loginToken.isRenewable();
				}).isPresent();
	}

	private void scheduleRenewal() {

		logger.info("Scheduling Token renewal");

		LoginToken loginToken = (LoginToken) token.get();
		final int seconds = NumberUtils.convertNumberToTargetClass(
				Math.max(1, loginToken.getLeaseDuration() - REFRESH_PERIOD_BEFORE_EXPIRY),
				Integer.class);

		final Runnable task = () -> {
			try {
				if (LifecycleAwareSessionManager.this.token != null
						&& isTokenRenewable()) {
					if (renewToken()) {
						scheduleRenewal();
					}
				}
			}
			catch (Exception e) {
				logger.error("Cannot renew VaultToken", e);
			}
		};

		scheduleTask(taskScheduler, seconds, task);
	}

	private void scheduleTask(TaskScheduler taskScheduler, int seconds, Runnable task) {
		taskScheduler.schedule(task, new OneShotTrigger(seconds));
	}

	/**
	 * This one-shot trigger creates only one execution time to trigger an execution only
	 * once.
	 */
	private static class OneShotTrigger implements Trigger {

		private final AtomicBoolean fired = new AtomicBoolean();
		private final int seconds;

		OneShotTrigger(int seconds) {
			this.seconds = seconds;
		}

		@Override
		public Date nextExecutionTime(TriggerContext triggerContext) {

			if (fired.compareAndSet(false, true)) {
				return new Date(
						System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds));
			}

			return null;
		}
	}
}
