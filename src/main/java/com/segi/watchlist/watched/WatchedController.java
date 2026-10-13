package com.segi.watchlist.watched;

import com.segi.watchlist.catalog.MovieCatalogService;
import com.segi.watchlist.cloud.FirebaseCloudException;
import com.segi.watchlist.cloud.FirebaseSessionPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WatchedController {
    private final WatchedService watched;

    public WatchedController(WatchedService watched) {
        this.watched = watched;
    }

    @GetMapping("/watched")
    public String index(Model model, Authentication authentication) {
        model.addAttribute("activePage", "watched");
        model.addAttribute("signedIn", true);
        model.addAttribute("currentEmail", authentication.getName());
        if (!watched.synchronize(authentication.getName(), principal(authentication))) {
            model.addAttribute("notice", "Showing the saved local copy. Firebase cloud sync is unavailable.");
        }
        model.addAttribute("entries", watched.list(authentication.getName()));
        return "watched/index";
    }

    @PostMapping("/watched/add")
    public String add(@RequestParam long movieId,
                      @RequestParam(defaultValue = "MOVIE") String mediaType,
                      Authentication authentication,
                      RedirectAttributes redirectAttributes) {
        try {
            watched.addMedia(authentication.getName(), movieId, mediaType, principal(authentication));
            redirectAttributes.addFlashAttribute("notice", "Added to your watched history.");
        } catch (FirebaseCloudException exception) {
            redirectAttributes.addFlashAttribute("notice", exception.getMessage());
            return "redirect:/watched";
        } catch (MovieCatalogService.TmdbNotConfiguredException | RestClientException exception) {
            redirectAttributes.addFlashAttribute("notice", "Could not load this title. Try again later.");
            return "redirect:/" + ("TV".equalsIgnoreCase(mediaType) ? "tv" : "movie") + "/" + movieId;
        }
        return "redirect:/watched";
    }

    @PostMapping("/watched/{entryId}/rating")
    public String rate(@PathVariable long entryId, @RequestParam int rating,
                       Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            watched.rate(authentication.getName(), entryId, rating, principal(authentication));
            redirectAttributes.addFlashAttribute("notice", "Your rating was saved.");
        } catch (FirebaseCloudException exception) {
            redirectAttributes.addFlashAttribute("notice", exception.getMessage());
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("notice", "Choose a rating from one to five stars.");
        }
        return "redirect:/watched";
    }

    @PostMapping("/watched/{entryId}/remove")
    public String remove(@PathVariable long entryId, Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        try {
            watched.remove(authentication.getName(), entryId, principal(authentication));
            redirectAttributes.addFlashAttribute("notice", "Removed from your watched history.");
        } catch (FirebaseCloudException exception) {
            redirectAttributes.addFlashAttribute("notice", exception.getMessage());
        }
        return "redirect:/watched";
    }

    private FirebaseSessionPrincipal principal(Authentication authentication) {
        return authentication.getPrincipal() instanceof FirebaseSessionPrincipal firebase ? firebase : null;
    }
}
