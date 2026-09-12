package com.myshop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.myshop.context.RequestContext;
import com.myshop.model.request.ChangePasswordRequest;
import com.myshop.model.response.UserProfileResponse;
import com.myshop.service.AuthCookieService;
import com.myshop.service.UserAvatarService;
import com.myshop.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;
    @Mock
    private UserAvatarService userAvatarService;
    @Mock
    private AuthCookieService authCookieService;

    private MockMvc mockMvc;
    private UserController controller;
    private UUID currentUserId;

    @BeforeEach
    void setUp() {
        controller = new UserController(userService, userAvatarService, authCookieService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        currentUserId = UUID.randomUUID();
        RequestContext.set(new RequestContext.UserInfo(currentUserId, "buyer", "USER"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void profileSerializesThePersistedAvatarUrlInSnakeCase() throws Exception {
        UserProfileResponse profile = new UserProfileResponse();
        profile.setAvatarUrl("/api/uploads/avatars/avatar-test.png");
        when(userService.getProfile(currentUserId)).thenReturn(profile);

        String json = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .writeValueAsString(controller.getProfile());

        assertTrue(json.contains("\"avatar_url\":\"/api/uploads/avatars/avatar-test.png\""));

        UserProfileResponse emptyAvatarProfile = new UserProfileResponse();
        String emptyAvatarJson = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .writeValueAsString(emptyAvatarProfile);
        assertTrue(emptyAvatarJson.contains("\"avatar_url\":null"));
    }

    @Test
    void avatarUploadAlwaysReceivesRequestContextUserId() throws Exception {
        UserProfileResponse profile = new UserProfileResponse();
        when(userAvatarService.uploadAvatar(eq(currentUserId), any())).thenReturn(profile);
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/user/avatar").file(file))
                .andExpect(status().isOk());

        verify(userAvatarService).uploadAvatar(eq(currentUserId), any());
    }

    @Test
    void passwordChangeClearsBothCookiesOnlyAfterTheServiceSucceeds() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old-password");
        request.setNewPassword("new-password");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.changePassword(request, response);

        verify(userService).changePassword(currentUserId, request);
        verify(authCookieService).clearAuthCookies(response);
    }
}
