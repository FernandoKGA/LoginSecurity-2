/*
 * This file is a part of LoginSecurity.
 *
 * Copyright (c) 2017 Lennart ten Wolde
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

package com.lenis0012.bukkit.loginsecurity.session;

import com.lenis0012.bukkit.loginsecurity.LoginSecurity;
import com.lenis0012.bukkit.loginsecurity.events.AuthActionEvent;
import com.lenis0012.bukkit.loginsecurity.events.AuthModeChangedEvent;
import com.lenis0012.bukkit.loginsecurity.session.action.ActionCallback;
import com.lenis0012.bukkit.loginsecurity.session.action.ActionResponse;
import com.lenis0012.bukkit.loginsecurity.session.exceptions.ProfileRefreshException;
import com.lenis0012.bukkit.loginsecurity.storage.AbstractEntity;
import com.lenis0012.bukkit.loginsecurity.storage.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Player session
 */
public class PlayerSession {
    private PlayerProfile profile;
    private AuthMode mode;

    protected PlayerSession(PlayerProfile profile, AuthMode mode) {
        this.profile = profile;
        this.mode = mode;
    }

    /**
     * Get session player's profile.
     *
     * @return Profile
     */
    public PlayerProfile getProfile() {
        return profile;
    }

    /**
     * Save the profile on a seperate thread.
     */
    public void saveProfileAsync() {
        if(!isRegistered()) {
            throw new IllegalStateException("Can't save profile when not registered!");
        }
        LoginSecurity.getExecutorService().execute(this::saveProfile);
    }

    public void saveProfile() {
        if(!isRegistered()) {
            throw new IllegalStateException("Can't save profile when not registered!");
        }

        if(profile.getLoginLocation() != null) {
            switch (profile.getLoginLocation().getState()) {
                case NEW:
                    LoginSecurity.dao().getLocationDao().insertLocation(profile.getLoginLocation());
                    break;
                case CHANGED:
                    LoginSecurity.dao().getLocationDao().updateLocation(profile.getLoginLocation());
                    break;
            }
        }

        if(profile.getInventory() != null) {
            switch (profile.getInventory().getState()) {
                case NEW:
                    LoginSecurity.getInstance().getLogger().info("Inserting inventory");
                    LoginSecurity.dao().getInventoryDao().insertInventory(profile.getInventory());
                    break;
                case CHANGED:
                    LoginSecurity.dao().getInventoryDao().updateInventory(profile.getInventory());
                    break;
            }
        }

        if(profile.getState() == AbstractEntity.State.CHANGED) {
            LoginSecurity.dao().getProfileDao().updateProfile(profile);
        }
    }

    /**
     * Refreshes player's profile.
     */
    public void refreshProfile() throws ProfileRefreshException {
//        final EbeanServer database = LoginSecurity.getInstance().getDatabase();
//        PlayerProfile newProfile = database.find(PlayerProfile.class).where().ieq("unique_user_id", profile.getUniqueUserId()).findUnique();
        PlayerProfile newProfile = LoginSecurity.dao().getProfileDao().findByUniqueUserId(profile.getUserId());

        if(newProfile != null && !isRegistered()) {
            throw new ProfileRefreshException("Profile was registered while in database!");
        }

        if(newProfile == null && isRegistered()) {
            throw new ProfileRefreshException("Profile was not found, even though it should be there!");
        }

        if(newProfile == null) {
            // Player isn't registered, nothing to update.
            return;
        }

        this.profile = newProfile;
    }

    /**
     * Reset the player's profile to a blank profile.
     */
    public void resetProfile() {
        this.profile = LoginSecurity.getSessionManager().createBlankProfile(UUID.fromString(profile.getUniqueUserId()));
    }

    /**
     * Check whether the player has an account and is logged in.
     * Note: You're probably looking for {@link #isAuthorized() isAuthorized}.
     *
     * @return Logged in
     */
    public boolean isLoggedIn() {
        return isAuthorized() && profile.getPassword() != null;
    }

    /**
     * Check whether or not the player's auth mode is "AUTHENTICATED".
     * This means they're allowed to move etc.
     * Returns true when player is logged in OR password is not required and player has no account.
     *
     * @return Authorized
     */
    public boolean isAuthorized() {
        return mode == AuthMode.AUTHENTICATED;
    }

    /**
     * Check whether or not player is registered.
     *
     * @return True if registered, False otherwise
     */
    public boolean isRegistered() {
        return profile.getPassword() != null;
    }

    /**
     * Get the player's current auth mode.
     *
     * @return Auth mode
     */
    public AuthMode getAuthMode() {
        return mode;
    }

    /**
     * Get the player for this session if player is online.
     *
     * @return Player
     */
    public Player getPlayer() {
        return Bukkit.getPlayer(profile.getLastName());
    }

    /**
     * Perform an action in an async task.
     * Runs callback when action is finished.
     *
     * @param action Action to perform
     * @param callback To run when action has been performed.
     */
    public void performActionAsync(final AuthAction action, final ActionCallback callback) {
        LoginSecurity.getExecutorService().execute(() -> {
            final ActionResponse response = performAction(action);
            Bukkit.getScheduler().runTask(LoginSecurity.getInstance(), () -> callback.call(response));
        });
    }

    /**
     * Perform an action on this session.
     *
     * @param action to perform
     */
    public ActionResponse performAction(AuthAction action) {
        AuthActionEvent event = new AuthActionEvent(this, action);
        Bukkit.getPluginManager().callEvent(event);
        if(event.isCancelled()) {
            return new ActionResponse(false, event.getCancelledMessage());
        }

        // Run
        final ActionResponse response = new ActionResponse();
        AuthMode previous = mode;
        AuthMode current = action.run(this, response);
        if(current == null || !response.isSuccess()) return response; // Something went wrong
        this.mode = current;

        // If auth mode changed, run event
        if(previous != mode) {
            AuthModeChangedEvent event1 = new AuthModeChangedEvent(this, previous, mode);
            Bukkit.getPluginManager().callEvent(event1);
        }

        // Complete
        return response;
    }
}
