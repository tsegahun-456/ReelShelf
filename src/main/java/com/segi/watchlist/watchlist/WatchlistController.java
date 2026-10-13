package com.segi.watchlist.watchlist;

import org.springframework.security.core.Authentication;
import com.segi.watchlist.cloud.FirebaseCloudException;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import com.segi.watchlist.catalog.MovieCatalogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WatchlistController {
    private final WatchlistService watchlist;

    public WatchlistController(WatchlistService watchlist) {
        this.watchlist = watchlist;
    }

    @GetMapping("/watchlist")
    public String index(Model model, Authentication authentication) {
        model.addAttribute("activePage", "watchlist");
        model.addAttribute("signedIn", true);
        model.addAttribute("currentEmail", authentication.getName());
        if (!watchlist.synchronize(authentication.getName(), principal(authentication))) {
            model.addAttribute("notice", "Showing the saved local copy. Firebase cloud sync is unavailable.");
        }
        model.addAttribute("entries", watchlist.list(authentication.getName()));
        return "watchlist/index";
    }

    @PostMapping("/watchlist/add")
    public String add(@RequestParam long movieId,
                      @RequestParam(defaultValue = "MOVIE") String mediaType,
                      Authentication authentication,
                      RedirectAttributes redirectAttributes) {
        try {
            watchlist.addMedia(authentication.getName(), movieId, mediaType, principal(authentication));
            redirectAttributes.addFlashAttribute("notice", "Added to your watchlist.");
            return "redirect:/watchlist";
        } catch (FirebaseCloudException exception) {
            redirectAttributes.addFlashAttribute("notice", exception.getMessage());
            return "redirect:/watchlist";
        } catch (MovieCatalogService.TmdbNotConfiguredException | RestClientException exception) {
            redirectAttributes.addFlashAttribute("notice", "Could not load this title. Try again later.");
            return "redirect:/" + ("TV".equalsIgnoreCase(mediaType) ? "tv" : "movie") + "/" + movieId;
        }
    }

    @PostMapping("/watchlist/{entryId}/remove")
    public String remove(@PathVariable long entryId, Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        try {
            watchlist.remove(authentication.getName(), entryId, principal(authentication));
            redirectAttributes.addFlashAttribute("notice", "Removed from your watchlist.");
        } catch (FirebaseCloudException exception) {
            redirectAttributes.addFlashAttribute("notice", exception.getMessage());
        }
        return "redirect:/watchlist";
    }

    private FirebaseSessionPrincipal principal(Authentication authentication) {
        return authentication.getPrincipal() instanceof FirebaseSessionPrincipal firebase ? firebase : null;
    }
}
