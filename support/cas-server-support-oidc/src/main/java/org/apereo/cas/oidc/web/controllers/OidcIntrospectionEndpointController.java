package org.apereo.cas.oidc.web.controllers;

import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationManager;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.oidc.OidcConstants;
import org.apereo.cas.oidc.introspection.OidcIntrospectionAccessTokenResponse;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.oauth.OAuth20Constants;
import org.apereo.cas.support.oauth.profile.OAuth20ProfileScopeToAttributesFilter;
import org.apereo.cas.support.oauth.services.OAuthRegisteredService;
import org.apereo.cas.support.oauth.util.OAuth20Utils;
import org.apereo.cas.support.oauth.validator.OAuth20Validator;
import org.apereo.cas.support.oauth.web.endpoints.BaseOAuth20Controller;
import org.apereo.cas.ticket.accesstoken.AccessToken;
import org.apereo.cas.ticket.accesstoken.AccessTokenFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.util.CollectionUtils;
import org.apereo.cas.web.support.CookieRetrievingCookieGenerator;
import org.apereo.cas.web.support.WebUtils;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.credentials.extractor.BasicAuthExtractor;
import org.pac4j.core.credentials.extractor.CredentialsExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;

/**
 * This is {@link OidcIntrospectionEndpointController}.
 *
 * @author Misagh Moayyed
 * @since 5.2.0
 */
public class OidcIntrospectionEndpointController extends BaseOAuth20Controller {
    private static final Logger LOGGER = LoggerFactory.getLogger(OidcIntrospectionEndpointController.class);

    private final CentralAuthenticationService centralAuthenticationService;

    public OidcIntrospectionEndpointController(final ServicesManager servicesManager,
                                               final TicketRegistry ticketRegistry,
                                               final OAuth20Validator validator,
                                               final AccessTokenFactory accessTokenFactory,
                                               final PrincipalFactory principalFactory,
                                               final ServiceFactory<WebApplicationService> webApplicationServiceServiceFactory,
                                               final OAuth20ProfileScopeToAttributesFilter scopeToAttributesFilter,
                                               final CasConfigurationProperties casProperties,
                                               final CookieRetrievingCookieGenerator cookieGenerator,
                                               final CentralAuthenticationService centralAuthenticationService) {
        super(servicesManager, ticketRegistry, validator, accessTokenFactory, principalFactory,
                webApplicationServiceServiceFactory,
                scopeToAttributesFilter, casProperties, cookieGenerator);
        this.centralAuthenticationService = centralAuthenticationService;
    }

    /**
     * Handle request.
     *
     * @param request  the request
     * @param response the response
     * @return the response entity
     * @throws Exception the exception
     */
    @GetMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE,
            value = {'/' + OidcConstants.BASE_OIDC_URL + '/' + OidcConstants.INTROSPECTION_URL})
    public ResponseEntity<OidcIntrospectionAccessTokenResponse> handleRequest(final HttpServletRequest request,
                                                                              final HttpServletResponse response) throws Exception {
        return handlePostRequest(request, response);
    }

    /**
     * Handle post request.
     *
     * @param request  the request
     * @param response the response
     * @return the response entity
     * @throws Exception the exception
     */
    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE,
            value = {'/' + OidcConstants.BASE_OIDC_URL + '/' + OidcConstants.INTROSPECTION_URL})
    public ResponseEntity<OidcIntrospectionAccessTokenResponse> handlePostRequest(final HttpServletRequest request,
                                                                                  final HttpServletResponse response) throws Exception {
        try {
            final CredentialsExtractor<UsernamePasswordCredentials> authExtractor = new BasicAuthExtractor(getClass().getSimpleName());
            final UsernamePasswordCredentials credentials = authExtractor.extract(WebUtils.getPac4jJ2EContext(request, response));
            if (credentials == null) {
                throw new IllegalArgumentException("No credentials are provided to verify introspection on the access token");
            }

            final OAuthRegisteredService service = OAuth20Utils.getRegisteredOAuthService(this.servicesManager, credentials.getUsername());
            if (this.validator.checkServiceValid(service)
                    && this.validator.checkParameterExist(request, OAuth20Constants.ACCESS_TOKEN)
                    && this.validator.checkClientSecret(service, credentials.getPassword())) {
                final String accessToken = request.getParameter(OAuth20Constants.ACCESS_TOKEN);
                final AccessToken ticket = this.centralAuthenticationService.getTicket(accessToken, AccessToken.class);
                if (ticket != null) {
                    final OidcIntrospectionAccessTokenResponse introspect = new OidcIntrospectionAccessTokenResponse();
                    introspect.setActive(true);
                    introspect.setClientId(service.getClientId());
                    final Authentication authentication = ticket.getAuthentication();
                    final String subject = authentication.getPrincipal().getId();
                    introspect.setSub(subject);
                    introspect.setUniqueSecurityName(subject);
                    introspect.setExp(ticket.getExpirationPolicy().getTimeToLive());
                    introspect.setIat(ticket.getCreationTime().toInstant().toEpochMilli());

                    final Object methods = authentication.getAttributes().get(AuthenticationManager.AUTHENTICATION_METHOD_ATTRIBUTE);
                    final String realmNames = CollectionUtils.toCollection(methods)
                            .stream()
                            .map(o -> o.toString())
                            .collect(Collectors.joining(","));

                    introspect.setRealmName(realmNames);
                    introspect.setTokenType(OAuth20Constants.TOKEN_TYPE_BEARER);

                    final String grant = authentication.getAttributes()
                            .getOrDefault(OAuth20Constants.GRANT_TYPE, StringUtils.EMPTY).toString().toLowerCase();
                    introspect.setGrantType(grant);
                    introspect.setScope(OidcConstants.OPENID);
                    introspect.setAud(service.getServiceId());
                    introspect.setIss(casProperties.getAuthn().getOidc().getIssuer());
                    return new ResponseEntity<>(introspect, HttpStatus.OK);
                }
            }
        } catch (final Exception e) {
            LOGGER.error(e.getMessage(), e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }
}
