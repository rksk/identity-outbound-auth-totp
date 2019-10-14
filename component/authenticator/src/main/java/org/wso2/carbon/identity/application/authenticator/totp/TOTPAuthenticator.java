/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.application.authenticator.totp;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.owasp.encoder.Encode;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.extension.identity.helper.FederatedAuthenticatorUtil;
import org.wso2.carbon.extension.identity.helper.IdentityHelperConstants;
import org.wso2.carbon.extension.identity.helper.util.IdentityHelperUtil;
import org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticatorFlowStatus;
import org.wso2.carbon.identity.application.authentication.framework.LocalApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.config.ConfigurationFacade;
import org.wso2.carbon.identity.application.authentication.framework.config.model.StepConfig;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.exception.LogoutFailedException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authenticator.totp.exception.TOTPException;
import org.wso2.carbon.identity.application.authenticator.totp.util.TOTPAuthenticatorConfig;
import org.wso2.carbon.identity.application.authenticator.totp.util.TOTPAuthenticatorCredentials;
import org.wso2.carbon.identity.application.authenticator.totp.util.TOTPKeyRepresentation;
import org.wso2.carbon.identity.application.authenticator.totp.util.TOTPUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Authenticator of TOTP.
 *
 * @since 2.0.3
 */
public class TOTPAuthenticator extends AbstractApplicationAuthenticator
		implements LocalApplicationAuthenticator {

	private static final long serialVersionUID = 2009231028659744926L;
	private static final Log log = LogFactory.getLog(TOTPAuthenticator.class);

	/**
	 * Check whether token or action are in request.
	 *
	 * @param request The http servlet request
	 * @return true, if token or action are not null
	 */
	@Override
	public boolean canHandle(HttpServletRequest request) {
		String token = request.getParameter(TOTPAuthenticatorConstants.TOKEN);
		String action = request.getParameter(TOTPAuthenticatorConstants.SEND_TOKEN);
		String enableTOTP = request.getParameter(TOTPAuthenticatorConstants.ENABLE_TOTP);
		return (token != null || action != null || enableTOTP != null);
	}

	/**
	 * This method is overridden to check additional condition like whether request is having
	 * sendToken, token parameters, generateTOTPToken and authentication name.
	 *
	 * @param request  Http servlet request
	 * @param response Http servlet response
	 * @param context  AuthenticationContext
	 * @return AuthenticatorFlowStatus
	 * @throws AuthenticationFailedException User tenant domain mismatch
	 * @throws LogoutFailedException         Error while checking logout request
	 */
	@Override
	public AuthenticatorFlowStatus process(HttpServletRequest request, HttpServletResponse response,
	                                       AuthenticationContext context)
			throws AuthenticationFailedException, LogoutFailedException {
		if (context.isLogoutRequest()) {
			return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
		} else if (request.getParameter(TOTPAuthenticatorConstants.SEND_TOKEN) != null) {
			if (generateTOTPToken(context)) {
				return AuthenticatorFlowStatus.INCOMPLETE;
			} else {
				return AuthenticatorFlowStatus.FAIL_COMPLETED;
			}
		} else if (StringUtils
				.isNotEmpty(request.getParameter(TOTPAuthenticatorConstants.ENABLE_TOTP))) {
			// if the request comes with MOBILE_NUMBER, it will go through this flow.
			initiateAuthenticationRequest(request, response, context);
			if (context.getProperty(TOTPAuthenticatorConstants.AUTHENTICATION)
			           .equals(TOTPAuthenticatorConstants.AUTHENTICATOR_NAME)) {
				return AuthenticatorFlowStatus.INCOMPLETE;
			} else {
				return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
			}
		} else if (request.getParameter(TOTPAuthenticatorConstants.TOKEN) == null) {
			initiateAuthenticationRequest(request, response, context);
			if (context.getProperty(TOTPAuthenticatorConstants.AUTHENTICATION)
			           .equals(TOTPAuthenticatorConstants.AUTHENTICATOR_NAME)) {
				return AuthenticatorFlowStatus.INCOMPLETE;
			} else {
				return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
			}
		} else {
			return super.process(request, response, context);
		}
	}

	/**
	 * Initiate authentication request.
	 *
	 * @param request  The request
	 * @param response The response
	 * @param context  The authentication context
	 * @throws AuthenticationFailedException If authenticatedUser could not be identified
	 */
	@Override
	protected void initiateAuthenticationRequest(HttpServletRequest request,
	                                             HttpServletResponse response,
	                                             AuthenticationContext context)
			throws AuthenticationFailedException {
		String username = null;
        String usecase;
        Object propertiesFromLocal = null;
		AuthenticatedUser authenticatedUser = null;
        Map<String, String> totpParameters = getAuthenticatorConfig().getParameterMap();

		String tenantDomain = context.getTenantDomain();
		context.setProperty(TOTPAuthenticatorConstants.AUTHENTICATION,
		                    TOTPAuthenticatorConstants.AUTHENTICATOR_NAME);
		if (!tenantDomain.equals(TOTPAuthenticatorConstants.SUPER_TENANT_DOMAIN)) {
			IdentityHelperUtil
					.loadApplicationAuthenticationXMLFromRegistry(context, getName(), tenantDomain);
            propertiesFromLocal = context.getProperty(IdentityHelperConstants.GET_PROPERTY_FROM_REGISTRY);
		}

        if (propertiesFromLocal != null || tenantDomain.equals(TOTPAuthenticatorConstants.SUPER_TENANT)) {
            usecase = totpParameters.get(TOTPAuthenticatorConstants.USECASE);
        } else {
            usecase = (String) context.getProperty(TOTPAuthenticatorConstants.USECASE);
        }

		String retryParam = "";
		try {

            if (StringUtils.isEmpty(usecase)) {
                // If 'usecase' property is not configured for email OTP authenticator, the below flow will be executed
                // (Recommended flow)
                setUsernameFromSubjectStep(context);
            } else {
                // If the attribute 'usecase' is configured, this block will be executed.
                // This block need to be revised and recommended to be removed
                FederatedAuthenticatorUtil.setUsernameFromFirstStep(context);
            }

			username = String.valueOf(context.getProperty(TOTPAuthenticatorConstants.USERNAME));
			authenticatedUser = (AuthenticatedUser) context.getProperty(TOTPAuthenticatorConstants.AUTHENTICATED_USER);

			if (authenticatedUser == null) {
				throw new AuthenticationFailedException
						("Authentication failed!. Cannot proceed further without identifying the user.");
			}
            if (username == null) {
                throw new AuthenticationFailedException
						("Authentication failed. Cannot find the subject attributed step with authenticated user.");
            }
			if (context.isRetrying()) {
				retryParam = "&authFailure=true&authFailureMsg=login.fail.message";
			}
			boolean isTOTPEnabled = isTOTPEnabledForLocalUser(username);
			if (log.isDebugEnabled()) {
				log.debug("TOTP is enabled by user: " + isTOTPEnabled);
			}
			boolean isTOTPEnabledByAdmin = IdentityHelperUtil.checkSecondStepEnableByAdmin(context);
			if (log.isDebugEnabled()) {
				log.debug("TOTP  is enabled by admin: " + isTOTPEnabledByAdmin);
			}
			String multiOptionURI = request.getParameter("multiOptionURI");
			multiOptionURI = multiOptionURI != null ? "&multiOptionURI=" + Encode.forUriComponent(multiOptionURI) : "";
			String totpLoginPageUrl =
					getLoginPage(context) + ("?sessionDataKey=" + context.getContextIdentifier()) +
					"&authenticators=" + getName() + "&type=totp" + retryParam + "&username=" +
					username + multiOptionURI;
			String totpErrorPageUrl =
					getErrorPage(context) + ("?sessionDataKey=" + context.getContextIdentifier()) +
					"&authenticators=" + getName() + "&type=totp_error" + retryParam +
					"&username=" + username + multiOptionURI;
			if (isTOTPEnabled && request.getParameter(TOTPAuthenticatorConstants.ENABLE_TOTP) == null) {
				//if TOTP is enabled for the user.
				response.sendRedirect(IdentityUtil.getServerURL(totpLoginPageUrl, true,
                        true));
			} else {
				if (TOTPUtil.isEnrolUserInAuthenticationFlowEnabled(context)
						&& request.getParameter(TOTPAuthenticatorConstants.ENABLE_TOTP) == null) {
					//if TOTP is not enabled for the user and he hasn't redirected to the enrolment page yet.
					if (log.isDebugEnabled()) {
						log.debug("User has not enabled TOTP: " + username);
					}
					Map<String, String> claims = TOTPKeyGenerator.generateClaims(username, false, context);
					context.setProperty(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL,
							claims.get(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL));
					context.setProperty(TOTPAuthenticatorConstants.QR_CODE_CLAIM_URL,
							claims.get(TOTPAuthenticatorConstants.QR_CODE_CLAIM_URL));
					String qrURL = claims.get(TOTPAuthenticatorConstants.QR_CODE_CLAIM_URL);
					TOTPUtil.redirectToEnableTOTPReqPage(request, response, context, qrURL);
				} else if (Boolean.valueOf(request.getParameter(TOTPAuthenticatorConstants.ENABLE_TOTP))) {
					//if TOTP is not enabled for the user and user continued the enrolment.
					context.setProperty(TOTPAuthenticatorConstants.ENABLE_TOTP, true);
					response.sendRedirect(totpLoginPageUrl);
				} else {
					if (isTOTPEnabledByAdmin) {
						//if TOTP is not enabled for the user and admin enforces TOTP.
						response.sendRedirect(totpErrorPageUrl);
					} else {
						//if admin does not enforces TOTP and TOTP is not enabled for the user.
						context.setSubject(authenticatedUser);
						StepConfig stepConfig = context.getSequenceConfig().getStepMap()
								.get(context.getCurrentStep() - 1);
						if (stepConfig.getAuthenticatedAutenticator()
								.getApplicationAuthenticator() instanceof LocalApplicationAuthenticator) {
							context.setProperty(TOTPAuthenticatorConstants.AUTHENTICATION,
									TOTPAuthenticatorConstants.BASIC);
						} else {
							context.setProperty(TOTPAuthenticatorConstants.AUTHENTICATION,
									TOTPAuthenticatorConstants.FEDERETOR);
						}
					}
				}
			}
		} catch (IOException e) {
			throw new AuthenticationFailedException(
					"Error when redirecting the TOTP login response, user : " + username, e);
		} catch (TOTPException e) {
			throw new AuthenticationFailedException(
					"Error when checking TOTP enabled for the user : " + username, e);
		} catch (AuthenticationFailedException e) {
			throw new AuthenticationFailedException(
					"Authentication failed!. Cannot get the username from first step.", e);
		}
	}

	/**
	 * Get the loginPage from authentication.xml file or use the login page from constant file.
	 *
	 * @param context the AuthenticationContext
	 * @return the loginPage
	 * @throws AuthenticationFailedException
	 */
	private String getLoginPage(AuthenticationContext context) throws AuthenticationFailedException {
		String loginPage = TOTPUtil.getLoginPageFromXMLFile(context, getName());
		if (StringUtils.isEmpty(loginPage)) {
			loginPage = ConfigurationFacade.getInstance().getAuthenticationEndpointURL()
					.replace(TOTPAuthenticatorConstants.LOGIN_PAGE, TOTPAuthenticatorConstants.TOTP_LOGIN_PAGE);
			if (log.isDebugEnabled()) {
				log.debug("Default endpoint is used");
			}
		}
		return loginPage;
	}

	/**
	 * Get the errorPage from authentication.xml file or use the error page from constant file.
	 *
	 * @param context the AuthenticationContext
	 * @return the errorPage
	 * @throws AuthenticationFailedException
	 */
	private String getErrorPage(AuthenticationContext context) throws AuthenticationFailedException {
		String errorPage = TOTPUtil.getErrorPageFromXMLFile(context, getName());
		if (StringUtils.isEmpty(errorPage)) {
			errorPage = ConfigurationFacade.getInstance().getAuthenticationEndpointURL()
					.replace(TOTPAuthenticatorConstants.LOGIN_PAGE, TOTPAuthenticatorConstants.ERROR_PAGE);
			if (log.isDebugEnabled()) {
				log.debug("Default endpoint is used");
			}
		}
		return errorPage;
	}

	/**
	 * This method is overridden to check validation of the given token.
	 *
	 * @param request  The http servlet request
	 * @param response The http servlet response
	 * @param context  AuthenticationContext
	 * @throws AuthenticationFailedException Authentication process failed for user
	 */
	@Override
	protected void processAuthenticationResponse(HttpServletRequest request,
	                                             HttpServletResponse response,
	                                             AuthenticationContext context)
			throws AuthenticationFailedException {
		String token = request.getParameter(TOTPAuthenticatorConstants.TOKEN);
		String username = context.getProperty(TOTPAuthenticatorConstants.USERNAME).toString();
		if (context.getProperty(TOTPAuthenticatorConstants.ENABLE_TOTP) != null && Boolean
				.valueOf(context.getProperty(TOTPAuthenticatorConstants.ENABLE_TOTP).toString())) {
			//adds the claims to the profile if the user enrol and continued.
			Map<String, String> claims = new HashMap<>();
            if (context.getProperty(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL) != null) {
                claims.put(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL,
                        context.getProperty(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL).toString());
            }
            if (context.getProperty(TOTPAuthenticatorConstants.QR_CODE_CLAIM_URL) != null) {
                claims.put(TOTPAuthenticatorConstants.QR_CODE_CLAIM_URL,
                        context.getProperty(TOTPAuthenticatorConstants.QR_CODE_CLAIM_URL).toString());
            }
			try {
				TOTPKeyGenerator.addTOTPClaimsAndRetrievingQRCodeURL(claims, username, context);
			} catch (TOTPException e) {
				throw new AuthenticationFailedException("Error while adding TOTP claims to the user : " + username, e);
			}
		}
		if (token != null) {
			try {
				int tokenValue = Integer.parseInt(token);
				if (!isValidTokenLocalUser(tokenValue, username, context)) {
					throw new AuthenticationFailedException(
							"Authentication failed, user :  " + username);
				}
				if (StringUtils.isNotBlank(username)) {
					AuthenticatedUser authenticatedUser = new AuthenticatedUser();
					authenticatedUser.setAuthenticatedSubjectIdentifier(username);
					authenticatedUser.setUserName(UserCoreUtil.removeDomainFromName(
							MultitenantUtils.getTenantAwareUsername(username)));
					authenticatedUser.setUserStoreDomain(UserCoreUtil.extractDomainFromName(username));
					authenticatedUser.setTenantDomain(MultitenantUtils.getTenantDomain(username));
					context.setSubject(authenticatedUser);
				} else {
					context.setSubject(AuthenticatedUser.createLocalAuthenticatedUserFromSubjectIdentifier(username));
				}
			} catch (TOTPException | NumberFormatException e) {
				throw new AuthenticationFailedException(
						"TOTP Authentication process failed for user " + username, e);
			}
		}
	}

	/**
	 * Check whether status of retrying authentication.
	 *
	 * @return true, if retry authentication is enabled
	 */
	@Override
	protected boolean retryAuthenticationEnabled() {
		return true;
	}

	/**
	 * Get requested session ID.
	 *
	 * @param request The http servlet request
	 * @return Requested session ID
	 */
	@Override
	public String getContextIdentifier(HttpServletRequest request) {
		return request.getRequestedSessionId();
	}

	/**
	 * Get friendly name.
	 *
	 * @return Authenticator friendly name
	 */
	@Override
	public String getFriendlyName() {
		return TOTPAuthenticatorConstants.AUTHENTICATOR_FRIENDLY_NAME;
	}

	/**
	 * Get authenticator name.
	 *
	 * @return Authenticator name
	 */
	@Override
	public String getName() {
		return TOTPAuthenticatorConstants.AUTHENTICATOR_NAME;
	}

	/**
	 * Generate TOTP token.
	 *
	 * @param context AuthenticationContext
	 * @return true, if token is generated successfully
	 */
	private boolean generateTOTPToken(AuthenticationContext context) {
        String username = context.getProperty(TOTPAuthenticatorConstants.USERNAME).toString();
        if (username == null) {
            log.error("No username found in the authentication context.");
            return false;
        } else {
            try {
                TOTPTokenGenerator.generateTOTPTokenLocal(username, context);
                if (log.isDebugEnabled()) {
                    log.debug("TOTP Token is generated");
                }
            } catch (TOTPException e) {
                log.error("Error when generating the totp token", e);
                return false;
            }
        }
        return true;
	}

	/**
	 * Check whether TOTP is enabled for local user or not.
	 *
	 * @param username Username of the user
	 * @return true, if TOTP enable for local user
	 * @throws TOTPException when user realm is null or could not find user
	 */
	private boolean isTOTPEnabledForLocalUser(String username)
			throws TOTPException, AuthenticationFailedException {
		UserRealm userRealm = TOTPUtil.getUserRealm(username);
		String tenantAwareUsername = null;
		try {
			tenantAwareUsername = MultitenantUtils.getTenantAwareUsername(username);
			if (userRealm != null) {
				Map<String, String> UserClaimValues =
						userRealm.getUserStoreManager().getUserClaimValues
								(tenantAwareUsername, new String[]
										{ TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL }, null);
				String secretKey =
						UserClaimValues.get(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL);
				return StringUtils.isNotBlank(secretKey);
			} else {
				throw new TOTPException(
						"Cannot find the user realm for the given tenant domain : " +
						CarbonContext.getThreadLocalCarbonContext().getTenantDomain());
			}
		} catch (UserStoreException e) {
			throw new TOTPException(
					"TOTPAccessController failed while trying to access userRealm of the user : " +
					tenantAwareUsername, e);
		}
	}

	/**
	 * Verify whether a given token is valid for a stored local user.
	 *
	 * @param token    TOTP Token which needs to be validated
	 * @param context  Authentication context
	 * @param username Username of the user
	 * @return true if token is valid otherwise false
	 * @throws TOTPException UserRealm for user or tenant domain is null
	 */
	private boolean isValidTokenLocalUser(int token, String username, AuthenticationContext context)
			throws TOTPException {
		TOTPKeyRepresentation encoding = TOTPKeyRepresentation.BASE32;
		String tenantDomain = MultitenantUtils.getTenantDomain(username);
		String tenantAwareUsername = null;
		try {
			if (TOTPAuthenticatorConstants.BASE64
					.equals(TOTPUtil.getEncodingMethod(tenantDomain, context))) {
				encoding = TOTPKeyRepresentation.BASE64;
			}
			long timeStep = TimeUnit.SECONDS.toMillis(TOTPUtil.getTimeStepSize(context));
			int windowSize = TOTPUtil.getWindowSize(context);
			TOTPAuthenticatorConfig.TOTPAuthenticatorConfigBuilder totpAuthenticatorConfigBuilder =
					new TOTPAuthenticatorConfig.TOTPAuthenticatorConfigBuilder()
							.setKeyRepresentation(encoding).setWindowSize(windowSize)
							.setTimeStepSizeInMillis(timeStep);
			TOTPAuthenticatorCredentials totpAuthenticator =
					new TOTPAuthenticatorCredentials(totpAuthenticatorConfigBuilder.build());
			tenantAwareUsername = MultitenantUtils.getTenantAwareUsername(username);
			UserRealm userRealm = TOTPUtil.getUserRealm(username);
			if (userRealm != null) {
				Map<String, String> userClaimValues = userRealm
						.getUserStoreManager().getUserClaimValues
								(tenantAwareUsername, new String[]
										{ TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL }, null);
				String secretKey = TOTPUtil.decrypt(
						userClaimValues.get(TOTPAuthenticatorConstants.SECRET_KEY_CLAIM_URL));
				return totpAuthenticator.authorize(secretKey, token);
			} else {
				throw new TOTPException(
						"Cannot find the user realm for the given tenant domain : " +
						CarbonContext.getThreadLocalCarbonContext().getTenantDomain());
			}
		} catch (UserStoreException e) {
			throw new TOTPException(
					"TOTPTokenVerifier failed while trying to access userRealm of the user : " +
					tenantAwareUsername, e);
		} catch (CryptoException e) {
			throw new TOTPException("Error while decrypting the key", e);
		} catch (AuthenticationFailedException e) {
			throw new TOTPException(
					"TOTPTokenVerifier cannot find the property value for encodingMethod");
		}
	}

	private void setUsernameFromSubjectStep(AuthenticationContext context) {

        String username = null;
        AuthenticatedUser authenticatedUser = null;
        Map<Integer, StepConfig> stepConfigMap = context.getSequenceConfig().getStepMap();
        // Iterate through the steps to identify from which step the user attributes need to extracted
        for (StepConfig stepConfig : stepConfigMap.values()) {
            authenticatedUser = stepConfig.getAuthenticatedUser();
            if (authenticatedUser != null && stepConfig.isSubjectAttributeStep()) {
                username = authenticatedUser.getUserName();
                break;
            }
        }
        context.setProperty(TOTPAuthenticatorConstants.AUTHENTICATED_USER, authenticatedUser);
        context.setProperty(TOTPAuthenticatorConstants.USERNAME, username);
    }
}
