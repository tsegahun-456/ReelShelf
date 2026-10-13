package com.segi.watchlist.account;

import com.segi.watchlist.cloud.FirebaseIdentityClient;
import com.segi.watchlist.cloud.FirebaseIdentityException;
import com.segi.watchlist.cloud.FirebaseProperties;
import com.segi.watchlist.cloud.FirebaseTokens;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.client.RestClientException;

@Controller
public class AccountController {
    private final AccountService accountService;
    private final FirebaseIdentityClient firebase;
    private final FirebaseProperties firebaseProperties;

    public AccountController(AccountService accountService, FirebaseIdentityClient firebase,
                             FirebaseProperties firebaseProperties) {
        this.accountService = accountService;
        this.firebase = firebase;
        this.firebaseProperties = firebaseProperties;
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("cloudSignInEnabled", firebaseProperties.firestoreConfigured());
        return "auth/login";
    }

    @GetMapping("/signup")
    public String signup(Model model) {
        model.addAttribute("form", new RegistrationForm());
        model.addAttribute("cloudSignInEnabled", firebaseProperties.firestoreConfigured());
        return "auth/signup";
    }

    @PostMapping("/signup")
    public String register(@Valid @ModelAttribute("form") RegistrationForm form,
                           BindingResult binding,
                           Model model) {
        if (!form.getPassword().equals(form.getConfirmPassword())) {
            binding.rejectValue("confirmPassword", "password.mismatch", "Passwords do not match.");
        }
        if (binding.hasErrors()) {
            model.addAttribute("cloudSignInEnabled", firebaseProperties.firestoreConfigured());
            return "auth/signup";
        }
        try {
            if (firebaseProperties.firestoreConfigured()) {
                if (accountService.hasAccount(form.getEmail())) {
                    model.addAttribute("accountError", "This email has saved local data. Sign in with its existing password to link and preserve that data.");
                    model.addAttribute("cloudSignInEnabled", true);
                    return "auth/signup";
                }
                FirebaseTokens tokens = firebase.signUp(form.getEmail(), form.getPassword(), form.getDisplayName());
                String email = tokens.email().isBlank() ? form.getEmail() : tokens.email();
                accountService.linkFirebaseIdentity(email, form.getDisplayName(), tokens.uid());
                return "redirect:/login?registered";
            }
            accountService.register(form.getDisplayName(), form.getEmail(), form.getPassword());
            return "redirect:/login?registered";
        } catch (DuplicateAccountException exception) {
            model.addAttribute("accountError", exception.getMessage());
            model.addAttribute("cloudSignInEnabled", firebaseProperties.firestoreConfigured());
            return "auth/signup";
        } catch (FirebaseIdentityException exception) {
            model.addAttribute("accountError", switch (exception.code()) {
                case "EMAIL_EXISTS" -> "An account with this email already exists. Sign in instead.";
                case "OPERATION_NOT_ALLOWED" -> "Email and password sign-in is not enabled in Firebase yet.";
                default -> "Firebase could not create the account. Check the Firebase setup and try again.";
            });
            model.addAttribute("cloudSignInEnabled", firebaseProperties.firestoreConfigured());
            return "auth/signup";
        } catch (AccountIdentityConflictException exception) {
            model.addAttribute("accountError", "This email is already linked to another account. Sign in with that account instead.");
            model.addAttribute("cloudSignInEnabled", firebaseProperties.firestoreConfigured());
            return "auth/signup";
        } catch (RestClientException exception) {
            model.addAttribute("accountError", "Firebase is unavailable right now. Check your internet connection and try again.");
            model.addAttribute("cloudSignInEnabled", firebaseProperties.firestoreConfigured());
            return "auth/signup";
        }
    }
}
