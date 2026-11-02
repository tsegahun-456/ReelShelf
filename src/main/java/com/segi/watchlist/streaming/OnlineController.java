package com.segi.watchlist.streaming;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Landing page for a future, separate online-streaming provider integration. */
@Controller
public class OnlineController {
    @GetMapping("/online")
    public String online(Model model, Authentication authentication) {
        boolean signedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        model.addAttribute("activePage", "online");
        model.addAttribute("signedIn", signedIn);
        model.addAttribute("currentEmail", signedIn ? authentication.getName() : "");
        return "online/index";
    }
}
