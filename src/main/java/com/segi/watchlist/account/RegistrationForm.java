package com.segi.watchlist.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegistrationForm {
    @NotBlank
    @Size(max = 80)
    private String displayName = "";

    @NotBlank
    @Email
    @Size(max = 254)
    private String email = "";

    @NotBlank
    @Size(min = 8, max = 72)
    private String password = "";

    @NotBlank
    private String confirmPassword = "";

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
}
