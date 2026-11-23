package com.meme.vwallet.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class UserPrincipal implements UserDetails {

    private final Long userId;
    private final String phoneNumber;
    private final String fullName;
    private final String jti;
    private final Instant expiresAt;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserPrincipal(Long userId, String phoneNumber, String fullName, String jti, Instant expiresAt) {
        this(userId, phoneNumber, fullName, jti, expiresAt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    public UserPrincipal(Long userId, String phoneNumber, String fullName, String jti, Instant expiresAt,
                         Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.phoneNumber = phoneNumber;
        this.fullName = fullName;
        this.jti = jti;
        this.expiresAt = expiresAt;
        this.authorities = authorities != null ? authorities : Collections.emptyList();
    }

    public Long getUserId() {
        return userId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getFullName() {
        return fullName;
    }

    public String getJti() {
        return jti;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return phoneNumber != null ? phoneNumber : String.valueOf(userId);
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserPrincipal that = (UserPrincipal) o;
        return Objects.equals(userId, that.userId) && Objects.equals(jti, that.jti);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, jti);
    }

    @Override
    public String toString() {
        return "UserPrincipal{" +
                "userId=" + userId +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", fullName='" + fullName + '\'' +
                ", jti='" + jti + '\'' +
                ", expiresAt=" + expiresAt +
                '}';
    }
}
