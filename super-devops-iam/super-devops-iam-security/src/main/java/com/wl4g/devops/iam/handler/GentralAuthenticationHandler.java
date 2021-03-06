/*
 * Copyright 2017 ~ 2025 the original author or authors.
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
package com.wl4g.devops.iam.handler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.session.Session;
import org.apache.shiro.session.SessionException;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.Assert;
import org.apache.shiro.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import static com.wl4g.devops.common.constants.IAMDevOpsConstants.CACHE_TICKET_S;
import static com.wl4g.devops.common.constants.IAMDevOpsConstants.KEY_PERMIT_ATTRIBUTE_NAME;
import static com.wl4g.devops.common.constants.IAMDevOpsConstants.KEY_ROLE_ATTRIBUTE_NAME;
import static com.wl4g.devops.common.constants.IAMDevOpsConstants.URI_C_BASE;
import static com.wl4g.devops.common.constants.IAMDevOpsConstants.URI_C_LOGOUT;
import static com.wl4g.devops.iam.sns.handler.SecondAuthcSnsHandler.SECOND_AUTHC_CACHE;
import static com.wl4g.devops.common.bean.iam.model.SecondAuthcAssertion.Status.ExpiredAuthorized;

import com.wl4g.devops.common.bean.iam.ApplicationInfo;
import com.wl4g.devops.common.bean.iam.model.LoggedModel;
import com.wl4g.devops.common.bean.iam.model.LogoutModel;
import com.wl4g.devops.common.bean.iam.model.SecondAuthcAssertion;
import com.wl4g.devops.common.bean.iam.model.SessionValidationAssertion;
import com.wl4g.devops.common.bean.iam.model.TicketAssertion;
import com.wl4g.devops.common.bean.iam.model.TicketAssertion.IamPrincipal;
import com.wl4g.devops.common.web.RespBase;
import com.wl4g.devops.common.bean.iam.model.TicketValidationModel;
import com.wl4g.devops.common.exception.iam.IamException;
import com.wl4g.devops.common.exception.iam.IllegalCallbackDomainException;
import com.wl4g.devops.common.exception.iam.InvalidGrantTicketException;
import com.wl4g.devops.common.exception.iam.IllegalApplicationAccessException;
import com.wl4g.devops.common.utils.lang.StringUtils2;
import com.wl4g.devops.common.utils.web.WebUtils2;
import com.wl4g.devops.iam.common.cache.EnhancedKey;
import com.wl4g.devops.iam.common.session.GrantTicketInfo;
import com.wl4g.devops.iam.common.session.IamSession;
import com.wl4g.devops.iam.common.session.mgt.IamSessionDAO;
import com.wl4g.devops.iam.common.utils.SessionBindings;
import com.wl4g.devops.iam.common.utils.Sessions;
import com.wl4g.devops.iam.context.ServerSecurityContext;
import com.wl4g.devops.support.cache.ScanCursor;

/**
 * Default authentication handler implements
 * 
 * @author Wangl.sir <983708408@qq.com>
 * @version v1.0
 * @date 2018年11月22日
 * @since
 */
public class GentralAuthenticationHandler extends AbstractAuthenticationHandler {
	final public static String SAVE_GRANT_SESSION = GentralAuthenticationHandler.class.getSimpleName() + ".GRANT_TICKET";

	final private static String[] PERMISSIVE_HOSTS = new String[] { "localhost", "127.0.0.1" };

	/**
	 * Iteration batch size.
	 */
	final private static int DEFAULT_BATCH_SIZE = 1000;

	@Autowired
	protected IamSessionDAO sessionDAO;

	public GentralAuthenticationHandler(ServerSecurityContext context, RestTemplate restTemplate) {
		super(context, restTemplate);
	}

	@Override
	public void checkAuthenticateRequests(String fromAppName, String redirectUrl) {
		// Check redirect URL(When source application is not empty)
		if (StringUtils.hasText(fromAppName)) {
			if (!StringUtils.hasText(redirectUrl)) {
				throw new IllegalCallbackDomainException("Redirect URL cannot be empty when source application is not empty");
			}

			// Application information
			ApplicationInfo appInfo = context.getApplicationInfo(fromAppName);
			if (appInfo == null || !StringUtils.hasText(appInfo.getAppName())
					|| !StringUtils.hasText(appInfo.getExtranetBaseUri())) {
				throw new IllegalCallbackDomainException(
						String.format("Illegal authorize callback domain configure, application[%s]", fromAppName));
			}
			if (log.isDebugEnabled()) {
				log.debug("Check authenticate requests application info:[{}]", appInfo);
			}

			// Check redirect URL are legitimate callback URI?(As long as there
			// is a match)
			try {
				String host = new URI(redirectUrl).getHost();
				if (!(StringUtils2.equalsAny(host, PERMISSIVE_HOSTS)
						|| WebUtils2.isEqualWithDomain(redirectUrl, appInfo.getExtranetBaseUri())
						|| WebUtils2.isEqualWithDomain(redirectUrl, appInfo.getIntranetBaseUri())
						|| WebUtils2.isEqualWithDomain(redirectUrl, appInfo.getViewExtranetBaseUri()))) {
					throw new IllegalCallbackDomainException(String.format("Illegal redirect URL [%s]", redirectUrl));
				}
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException(e);
			}
		}
	}

	@Override
	public void checkApplicationAccessAuthorized(String principal, String fromAppName) {
		Assert.hasText(principal, "'principal' must not be empty");
		Assert.hasText(fromAppName, "'fromAppName' must not be empty");
		if (!context.isApplicationAccessAuthorized(principal, fromAppName)) {
			throw new IllegalApplicationAccessException(
					bundle.getMessage("GentralAuthenticationHandler.unaccessible", principal, fromAppName));
		}
	}

	@Override
	public TicketAssertion validate(TicketValidationModel param) {
		TicketAssertion assertion = new TicketAssertion();
		String fromAppName = param.getApplication();
		Assert.hasText(fromAppName, "'fromAppName' must not be empty");

		// Get subject session of grantTicket
		/*
		 * Synchronize with xx.xx.session.mgt.IamSessionManager#getSessionId
		 */
		Subject subject = SecurityUtils.getSubject();
		if (log.isDebugEnabled()) {
			log.debug("Validate subject [{}] by grant ticket [{}]", subject, param.getTicket());
		}

		// Check grant ticket
		checkGrantTicketValidited(subject, param);

		// Check application access authorized
		checkApplicationAccessAuthorized((String) subject.getPrincipal(), fromAppName);

		// Force clearance of last grant Ticket
		/*
		 * Synchronize with
		 * xx.xx.handler.impl.FastCasAuthenticationHandler#validate#loggedin
		 */
		cacheManager.getCache(CACHE_TICKET_S).remove(new EnhancedKey(param.getTicket()));
		if (log.isDebugEnabled()) {
			log.debug("Clean used grant ticket. [{}]", param.getTicket());
		}

		// Get current grant ticket session.
		Session session = subject.getSession();

		// Grant attributes settings
		//
		// Principal
		String principal = (String) subject.getPrincipal();
		assertion.setPrincipal(new IamPrincipal(principal));

		// Grant validated start date.
		Calendar calendar = Calendar.getInstance();
		assertion.setValidFromDate(calendar.getTime());

		/*
		 * xx.xx...client.realm.FastCasAuthorizingRealm#doGetAuthenticationInfo
		 * Grant term of validity(end date).
		 */
		long expiredMs = SessionBindings.getSessionExpiredTime(session);
		calendar.add(Calendar.MILLISECOND, (int) expiredMs);
		assertion.setValidUntilDate(calendar.getTime());

		// Updating grantTicket
		/*
		 * Synchronize with
		 * xx.xx.handler.impl.FastCasAuthenticationHandler#logout<br/>
		 * xx.xx.session.mgt.IamSessionManager#getSessionId
		 */
		String newGrantTicket = idGenerator.generateId(session).toString();
		if (log.isInfoEnabled()) {
			log.info("New generate grant ticket. [{}]", newGrantTicket);
		}

		/*
		 * Re-bind granting session => applications
		 */
		bindGrantingSession(session, fromAppName, newGrantTicket);

		assertion.getAttributes().put(config.getParam().getGrantTicket(), newGrantTicket);
		// Find authorized roles and permission information settings.
		assertion.getPrincipal().getAttributes().put(KEY_ROLE_ATTRIBUTE_NAME, getRoles(principal, fromAppName));
		assertion.getPrincipal().getAttributes().put(KEY_PERMIT_ATTRIBUTE_NAME, getPermits(principal, fromAppName));

		return assertion;
	}

	@Override
	public LoggedModel loggedin(String fromAppName, Subject subject) {
		Assert.hasText(fromAppName, "'fromAppName' must not be empty");

		// Check authentication.
		if (subject != null && subject.isAuthenticated() && StringUtils.hasText((String) subject.getPrincipal())) {
			Session session = subject.getSession(); // Session

			// Generate grantTicket. Same: CAS/service-ticket
			String initGrantTicket = idGenerator.generateId(session).toString();
			if (log.isInfoEnabled()) {
				log.info("Generate init grantTicket:[{}] by name:[{}]", initGrantTicket, fromAppName);
			}

			// Initial bind granting session => applications
			bindGrantingSession(session, fromAppName, initGrantTicket);

			// Return redirection information
			return new LoggedModel(initGrantTicket);
		}
		throw new AuthenticationException("Unauthenticated");
	}

	@Override
	public LogoutModel logout(boolean forced, String fromAppName, HttpServletRequest request, HttpServletResponse response) {
		if (log.isDebugEnabled()) {
			log.debug("Logout from[{}], forced[{}], sessionId[{}]", fromAppName, forced, Sessions.getSessionId());
		}
		Subject subject = SecurityUtils.getSubject();

		// Execution listener
		coprocessor.preLogout(forced, request, response);

		// Represents all logged-out Tags
		boolean logoutAll = false;

		// Get bind session grant information
		GrantTicketInfo grantInfo = getGrantTicketSeesion(subject.getSession());
		if (log.isDebugEnabled()) {
			log.debug("Get grant information bound the session is [{}]", grantInfo);
		}

		if (grantInfo != null && grantInfo.hasApplications()) {
			/*
			 * Query applications by bind session names
			 */
			Set<String> appNames = grantInfo.getApplications().keySet();
			List<ApplicationInfo> apps = context.findApplicationInfo(appNames.toArray(new String[] {}));
			if (apps == null || apps.isEmpty()) {
				throw new IamException(String.format("Find application information is empty. %s", appNames));
			}

			// logout all
			logoutAll = processLogoutAll(subject, grantInfo, apps);
		}

		if (forced || logoutAll) {
			/*
			 * Logout server session
			 */
			try {
				// That's the subject Refer to
				// com.wl4g.devops.iam.session.mgt.IamSessionManager#getSessionId
				// try/catch added for SHIRO-298:
				subject.logout();

				if (log.isDebugEnabled()) {
					log.debug("Local logout finished. sessionId[{}]", Sessions.getSessionId(subject));
				}

			} catch (SessionException e) {
				log.warn("Encountered session exception during logout. This can generally safely be ignored.", e);
			}
		}

		return StringUtils.hasText(fromAppName) ? new LogoutModel(fromAppName) : new LogoutModel();
	}

	@Override
	public SecondAuthcAssertion secondValidate(String secondAuthCode, String fromAppName) {
		EnhancedKey ekey = new EnhancedKey(secondAuthCode, SecondAuthcAssertion.class);
		try {
			/*
			 * Save authorized info to cache. See:
			 * xx.iam.sns.handler.SecondAuthcSnsHandler#afterCallbackSet()
			 */
			SecondAuthcAssertion assertion = (SecondAuthcAssertion) cacheManager.getEnhancedCache(SECOND_AUTHC_CACHE).get(ekey);
			// Check assertion expired
			if (assertion == null) {
				assertion = new SecondAuthcAssertion(ExpiredAuthorized);
				assertion.setErrdesc("Authorization expires, please re-authorize.");
			}
			return assertion;
		} finally { // Release authentication code
			if (log.isInfoEnabled()) {
				log.info("Remove release second authentication info. key[{}]", new String(ekey.getKey()));
			}
			cacheManager.getEnhancedCache(SECOND_AUTHC_CACHE).remove(ekey);
		}
	}

	@Override
	public SessionValidationAssertion sessionValidate(SessionValidationAssertion assertion) {
		Assert.hasText(assertion.getApplication(), "'application' cannot not be empty");

		ScanCursor<IamSession> cursor = sessionDAO.getActiveSessions(DEFAULT_BATCH_SIZE);
		while (cursor.hasNext()) {
			Session session = cursor.next();
			// GrantTicket by session
			GrantTicketInfo info = getGrantTicketSeesion(session);

			if (info != null && info.hasApplications()) {
				String savedGrantTicket = info.getApplications().get(assertion.getApplication());
				// If exist grantTicket with application
				if (savedGrantTicket != null) {
					assertion.getTickets().remove(savedGrantTicket);
				}
			}
		}

		return assertion;
	}

	/**
	 * Get bind session grant ticket information
	 * 
	 * @param session
	 * @return
	 */
	private GrantTicketInfo getGrantTicketSeesion(Session session) {
		return (GrantTicketInfo) session.getAttribute(SAVE_GRANT_SESSION);
	}

	/**
	 * Cover bind session => grant application<br/>
	 * 
	 * @param session
	 *            Session
	 * @param grantApp
	 *            granting application name
	 * @param grantTicket
	 *            grant ticket
	 */
	private void bindGrantingSession(Session session, String grantApp, String grantTicket) {
		Assert.notNull(session, "'session' must not be null");
		Assert.hasText(grantApp, "'grantApp' must not be empty");
		Assert.isTrue(StringUtils.hasText(grantTicket), "'grantTicket' must not be null");

		/*
		 * Synchronize with See:DefaultAuthenticationHandler#validate()
		 */
		GrantTicketInfo info = getGrantTicketSeesion(session);
		if (info == null) {
			info = new GrantTicketInfo();
		}
		if (info.getApplications().keySet().contains(grantApp)) {
			if (log.isDebugEnabled()) {
				log.debug("Rebinding the session:[{}] application:[{}]", session.toString(), grantApp);
			}
		}
		/*
		 * Update grantTicket info and saved to session
		 */
		session.setAttribute(SAVE_GRANT_SESSION, info.addApplications(grantApp, grantTicket));
		if (log.isInfoEnabled()) {
			log.info("Update grantTicket info to session. {}", info);
		}

		// Get session expire time
		long expireTime = SessionBindings.getSessionExpiredTime(session);
		if (log.isDebugEnabled()) {
			log.debug("Get session Id:[{}] expire time:[{}]", session.getId(), expireTime);
		}

		// Save grantTicket => sessionId.
		/*
		 * Synchronize with
		 * xx.xx.handler.impl.FastCasAuthenticationHandler#validate<br/>
		 * xx.xx.session.mgt.IamSessionManager#getSessionId
		 */
		cacheManager.getEnhancedCache(CACHE_TICKET_S).put(new EnhancedKey(grantTicket, expireTime), session.getId().toString());
		if (log.isDebugEnabled()) {
			log.debug("Init grantTicket[{}] of seesionId[{}] ", grantTicket, Sessions.getSessionId(session));
		}

	}

	/**
	 * Check grant ticket validity <br/>
	 * {@link com.wl4g.devops.iam.handler.GentralAuthenticationHandler#loggedin}
	 * 
	 * @param subject
	 * @param param
	 */
	private void checkGrantTicketValidited(Subject subject, TicketValidationModel param) {
		if (!StringUtils.hasText(param.getTicket())) {
			throw new InvalidGrantTicketException(String.format("Invalid empty grant ticket '%s'", param.getTicket()));
		}

		// Get grant information
		GrantTicketInfo info = getGrantTicketSeesion(subject.getSession());
		if (log.isDebugEnabled()) {
			log.debug("Get grant information:{} by sessionId:{}", info, subject.getSession().getId().toString());
		}

		// Request fromAppName ticket => storedTicket
		String storedTicket = info.getApplications().get(param.getApplication());
		// Validation
		if (!(StringUtils2.equals(storedTicket, param.getTicket()) && subject.isAuthenticated()
				&& StringUtils2.isNotBlank((String) subject.getPrincipal()))) {
			throw new InvalidGrantTicketException(String.format("Invalid grant ticket '%s'", param.getTicket()));
		}
	}

	/**
	 * Processing logout all
	 * 
	 * @param subject
	 * @param grantInfo
	 * @param apps
	 * @return
	 */
	private boolean processLogoutAll(Subject subject, GrantTicketInfo grantInfo, List<ApplicationInfo> apps) {
		boolean logoutAll = true; // Represents all logged-out Tags

		/*
		 * Notification all logged-in applications to logout
		 */
		for (ApplicationInfo app : apps) {
			Assert.hasText(app.getIntranetBaseUri(),
					String.format("Application[%s] 'internalBaseUri' must not be empty", app.getAppName()));

			// GrantTicket by application name
			String grantTicket = grantInfo.getApplications().get(app.getAppName());

			// Application logout URL
			String url = new StringBuffer(app.getIntranetBaseUri()).append(URI_C_BASE).append("/").append(URI_C_LOGOUT)
					.append("?").append(config.getParam().getGrantTicket()).append("=").append(grantTicket).toString();

			// Post remote client logout
			try {
				RespBase<LogoutModel> resp = restTemplate
						.exchange(url, HttpMethod.POST, null, new ParameterizedTypeReference<RespBase<LogoutModel>>() {
						}).getBody();
				if (RespBase.isSuccess(resp)) {
					log.info("Logout finished for principal:{}, application:{} url:{}", subject.getPrincipal(), app.getAppName(),
							url);
				} else {
					throw new IamException(resp != null ? resp.getMessage() : "No response");
				}
			} catch (Exception e) {
				logoutAll = false;
				log.error(String.format("Remote client logout failure. principal[%s] application[%s] url[%s]",
						subject.getPrincipal(), app.getAppName(), url), e);
			}
		}

		return logoutAll;
	}

}