package com.segi.watchlist.profile;

import com.segi.watchlist.account.AccountService;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import com.segi.watchlist.watchlist.WatchlistService;
import com.segi.watchlist.watched.WatchedRepository;
import com.segi.watchlist.watchlist.WatchlistRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ProfileController {
    private final AccountService accounts;
    private final WatchlistRepository watchlist;
    private final WatchedRepository watched;
    private final WatchlistService watchlistService;

    public ProfileController(AccountService accounts, WatchlistRepository watchlist,
                             WatchedRepository watched, WatchlistService watchlistService) {
        this.accounts = accounts;
        this.watchlist = watchlist;
        this.watched = watched;
        this.watchlistService = watchlistService;
    }

    @GetMapping("/profile")
    public String profile(Authentication authentication, Model model) {
        FirebaseSessionPrincipal principal = authentication.getPrincipal() instanceof FirebaseSessionPrincipal firebase
                ? firebase : null;
        boolean cloudSynced = watchlistService.synchronize(authentication.getName(), principal);
        var account = accounts.getRequiredByEmail(authentication.getName());
        model.addAttribute("account", account);
        model.addAttribute("watchlistCount", watchlist.countByAccountId(account.getId()));
        model.addAttribute("watchedCount", watched.countByAccountId(account.getId()));
        model.addAttribute("activePage", "profile");
        model.addAttribute("signedIn", true);
        model.addAttribute("currentEmail", account.getEmail());
        if (!cloudSynced) model.addAttribute("notice", "Showing the saved local copy. Firebase cloud sync is unavailable.");
        return "profile/index";
    }
}
