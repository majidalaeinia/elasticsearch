/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.security.rest;

import com.nimbusds.jose.util.StandardCharset;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.http.HttpChannel;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestRequestFilter;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.SecuritySettingsSourceField;
import org.elasticsearch.test.rest.FakeRestRequest;
import org.elasticsearch.xcontent.DeprecationHandler;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.security.SecurityContext;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.core.security.authc.Authentication.RealmRef;
import org.elasticsearch.xpack.core.security.authc.support.SecondaryAuthentication;
import org.elasticsearch.xpack.core.security.user.XPackUser;
import org.elasticsearch.xpack.security.authc.AuthenticationService;
import org.elasticsearch.xpack.security.authc.support.SecondaryAuthenticator;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.test.ActionListenerUtils.anyActionListener;
import static org.elasticsearch.xpack.core.security.support.Exceptions.authenticationError;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class SecurityRestFilterTests extends ESTestCase {

    private ThreadContext threadContext;
    private AuthenticationService authcService;
    private SecondaryAuthenticator secondaryAuthenticator;
    private RestChannel channel;
    private SecurityRestFilter filter;
    private RestHandler restHandler;

    @Before
    public void init() throws Exception {
        authcService = mock(AuthenticationService.class);
        channel = mock(RestChannel.class);
        restHandler = mock(RestHandler.class);
        threadContext = new ThreadContext(Settings.EMPTY);
        secondaryAuthenticator = new SecondaryAuthenticator(Settings.EMPTY, threadContext, authcService);
        filter = new SecurityRestFilter(Settings.EMPTY, threadContext, authcService, secondaryAuthenticator, restHandler, false);
    }

    public void testProcess() throws Exception {
        RestRequest request = mock(RestRequest.class);
        when(request.getHttpChannel()).thenReturn(mock(HttpChannel.class));
        Authentication authentication = mock(Authentication.class);
        doAnswer((i) -> {
            @SuppressWarnings("unchecked")
            ActionListener<Authentication> callback = (ActionListener<Authentication>) i.getArguments()[1];
            callback.onResponse(authentication);
            return Void.TYPE;
        }).when(authcService).authenticate(eq(request), anyActionListener());
        filter.handleRequest(request, channel, null);
        verify(restHandler).handleRequest(request, channel, null);
        verifyNoMoreInteractions(channel);
    }

    public void testProcessSecondaryAuthentication() throws Exception {
        RestRequest request = mock(RestRequest.class);
        when(channel.request()).thenReturn(request);

        when(request.getHttpChannel()).thenReturn(mock(HttpChannel.class));

        Authentication primaryAuthentication = mock(Authentication.class);
        when(primaryAuthentication.encode()).thenReturn(randomAlphaOfLengthBetween(12, 36));
        doAnswer(i -> {
            final Object[] arguments = i.getArguments();
            @SuppressWarnings("unchecked")
            ActionListener<Authentication> callback = (ActionListener<Authentication>) arguments[arguments.length - 1];
            callback.onResponse(primaryAuthentication);
            return null;
        }).when(authcService).authenticate(eq(request), anyActionListener());

        Authentication secondaryAuthentication = mock(Authentication.class);
        when(secondaryAuthentication.encode()).thenReturn(randomAlphaOfLengthBetween(12, 36));
        doAnswer(i -> {
            final Object[] arguments = i.getArguments();
            @SuppressWarnings("unchecked")
            ActionListener<Authentication> callback = (ActionListener<Authentication>) arguments[arguments.length - 1];
            callback.onResponse(secondaryAuthentication);
            return null;
        }).when(authcService).authenticate(eq(request), eq(false), anyActionListener());

        SecurityContext securityContext = new SecurityContext(Settings.EMPTY, threadContext);
        AtomicReference<SecondaryAuthentication> secondaryAuthRef = new AtomicReference<>();
        doAnswer(i -> {
            secondaryAuthRef.set(securityContext.getSecondaryAuthentication());
            return null;
        }).when(restHandler).handleRequest(request, channel, null);

        final String credentials = randomAlphaOfLengthBetween(4, 8) + ":" + randomAlphaOfLengthBetween(4, 12);
        threadContext.putHeader(
            SecondaryAuthenticator.SECONDARY_AUTH_HEADER_NAME,
            "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharset.UTF_8))
        );
        filter.handleRequest(request, channel, null);
        verify(restHandler).handleRequest(request, channel, null);
        verifyNoMoreInteractions(channel);

        assertThat(secondaryAuthRef.get(), notNullValue());
        assertThat(secondaryAuthRef.get().getAuthentication(), sameInstance(secondaryAuthentication));
    }

    public void testProcessWithSecurityDisabled() throws Exception {
        Settings settings = Settings.builder().put(XPackSettings.SECURITY_ENABLED.getKey(), false).build();
        filter = new SecurityRestFilter(settings, threadContext, authcService, secondaryAuthenticator, restHandler, false);
        RestRequest request = mock(RestRequest.class);
        filter.handleRequest(request, channel, null);
        verify(restHandler).handleRequest(request, channel, null);
        verifyNoMoreInteractions(channel, authcService);
    }

    public void testProcessAuthenticationFailedNoTrace() throws Exception {
        filter = new SecurityRestFilter(Settings.EMPTY, threadContext, authcService, secondaryAuthenticator, restHandler, false);
        testProcessAuthenticationFailed(
            randomBoolean()
                ? authenticationError("failed authn")
                : authenticationError("failed authn with " + "cause", new ElasticsearchException("cause")),
            RestStatus.UNAUTHORIZED,
            true,
            true,
            false
        );
        testProcessAuthenticationFailed(
            randomBoolean()
                ? authenticationError("failed authn")
                : authenticationError("failed authn with " + "cause", new ElasticsearchException("cause")),
            RestStatus.UNAUTHORIZED,
            true,
            false,
            false
        );
        testProcessAuthenticationFailed(
            randomBoolean()
                ? authenticationError("failed authn")
                : authenticationError("failed authn with " + "cause", new ElasticsearchException("cause")),
            RestStatus.UNAUTHORIZED,
            false,
            true,
            false
        );
        testProcessAuthenticationFailed(
            randomBoolean()
                ? authenticationError("failed authn")
                : authenticationError("failed authn with " + "cause", new ElasticsearchException("cause")),
            RestStatus.UNAUTHORIZED,
            false,
            false,
            false
        );
        testProcessAuthenticationFailed(new ElasticsearchException("dummy"), RestStatus.INTERNAL_SERVER_ERROR, false, false, false);
        testProcessAuthenticationFailed(new IllegalArgumentException("dummy"), RestStatus.BAD_REQUEST, true, false, false);
        testProcessAuthenticationFailed(new ElasticsearchException("dummy"), RestStatus.INTERNAL_SERVER_ERROR, false, true, false);
        testProcessAuthenticationFailed(new IllegalArgumentException("dummy"), RestStatus.BAD_REQUEST, true, true, true);
    }

    private void testProcessAuthenticationFailed(
        Exception authnException,
        RestStatus expectedRestStatus,
        boolean errorTrace,
        boolean detailedErrorsEnabled,
        boolean traceExists
    ) throws Exception {
        RestRequest request;
        if (errorTrace != ElasticsearchException.REST_EXCEPTION_SKIP_STACK_TRACE_DEFAULT == false || randomBoolean()) {
            request = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY).withParams(
                Map.of("error_trace", Boolean.toString(errorTrace))
            ).build();
        } else {
            // sometimes do not fill in the default value
            request = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY).build();
        }
        doAnswer((i) -> {
            ActionListener<?> callback = (ActionListener<?>) i.getArguments()[1];
            callback.onFailure(authnException);
            return Void.TYPE;
        }).when(authcService).authenticate(eq(request), anyActionListener());
        RestChannel channel = mock(RestChannel.class);
        when(channel.detailedErrorsEnabled()).thenReturn(detailedErrorsEnabled);
        when(channel.request()).thenReturn(request);
        when(channel.newErrorBuilder()).thenReturn(JsonXContent.contentBuilder());
        filter.handleRequest(request, channel, null);
        ArgumentCaptor<BytesRestResponse> response = ArgumentCaptor.forClass(BytesRestResponse.class);
        verify(channel).sendResponse(response.capture());
        RestResponse restResponse = response.getValue();
        assertThat(restResponse.status(), is(expectedRestStatus));
        if (traceExists) {
            assertThat(restResponse.content().utf8ToString(), containsString(ElasticsearchException.STACK_TRACE));
        } else {
            assertThat(restResponse.content().utf8ToString(), not(containsString(ElasticsearchException.STACK_TRACE)));
        }
        verifyNoMoreInteractions(restHandler);
    }

    public void testProcessOptionsMethod() throws Exception {
        RestRequest request = mock(RestRequest.class);
        when(request.method()).thenReturn(RestRequest.Method.OPTIONS);
        filter.handleRequest(request, channel, null);
        verify(restHandler).handleRequest(request, channel, null);
        verifyNoMoreInteractions(channel);
        verifyNoMoreInteractions(authcService);
    }

    public void testProcessFiltersBodyCorrectly() throws Exception {
        FakeRestRequest restRequest = new FakeRestRequest.Builder(NamedXContentRegistry.EMPTY).withContent(
            new BytesArray("{\"password\": \"" + SecuritySettingsSourceField.TEST_PASSWORD + "\", \"foo\": \"bar\"}"),
            XContentType.JSON
        ).build();
        when(channel.request()).thenReturn(restRequest);
        SetOnce<RestRequest> handlerRequest = new SetOnce<>();
        restHandler = new FilteredRestHandler() {
            @Override
            public void handleRequest(RestRequest request, RestChannel channel, NodeClient client) throws Exception {
                handlerRequest.set(request);
            }

            @Override
            public Set<String> getFilteredFields() {
                return Collections.singleton("password");
            }
        };
        SetOnce<RestRequest> authcServiceRequest = new SetOnce<>();
        doAnswer((i) -> {
            @SuppressWarnings("unchecked")
            ActionListener<Authentication> callback = (ActionListener<Authentication>) i.getArguments()[1];
            authcServiceRequest.set((RestRequest) i.getArguments()[0]);
            callback.onResponse(new Authentication(XPackUser.INSTANCE, new RealmRef("test", "test", "t"), null));
            return Void.TYPE;
        }).when(authcService).authenticate(any(RestRequest.class), anyActionListener());
        filter = new SecurityRestFilter(Settings.EMPTY, threadContext, authcService, secondaryAuthenticator, restHandler, false);

        filter.handleRequest(restRequest, channel, null);

        assertEquals(restRequest, handlerRequest.get());
        assertEquals(restRequest.content(), handlerRequest.get().content());
        Map<String, Object> original = XContentType.JSON.xContent()
            .createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                handlerRequest.get().content().streamInput()
            )
            .map();
        assertEquals(2, original.size());
        assertEquals(SecuritySettingsSourceField.TEST_PASSWORD, original.get("password"));
        assertEquals("bar", original.get("foo"));

        assertNotEquals(restRequest, authcServiceRequest.get());
        assertNotEquals(restRequest.content(), authcServiceRequest.get().content());

        Map<String, Object> map = XContentType.JSON.xContent()
            .createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                authcServiceRequest.get().content().streamInput()
            )
            .map();
        assertEquals(1, map.size());
        assertEquals("bar", map.get("foo"));
    }

    private interface FilteredRestHandler extends RestHandler, RestRequestFilter {}
}
