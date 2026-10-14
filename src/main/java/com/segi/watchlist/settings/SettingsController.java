package com.segi.watchlist.settings;

import com.segi.watchlist.library.MediaRootService;
import com.segi.watchlist.library.MediaScanner;
import java.util.concurrent.CompletableFuture;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Settings page: which folders the local library scans (add, remove, detect, scan now). */
@Controller
public class SettingsController {
    private final MediaRootService mediaRoots;
    private final MediaScanner scanner;

    public SettingsController(MediaRootService mediaRoots, MediaScanner scanner) {
        this.mediaRoots = mediaRoots;
        this.scanner = scanner;
    }

    @GetMapping("/settings")
    public String settings(Model model, Authentication authentication) {
        model.addAttribute("activePage", "settings");
        model.addAttribute("signedIn", true);
        model.addAttribute("currentEmail", authentication == null ? "" : authentication.getName());
        model.addAttribute("folders", mediaRoots.folders());
        model.addAttribute("suggestions", mediaRoots.suggestions());
        model.addAttribute("homeFolder", mediaRoots.homeFolder());
        model.addAttribute("everythingFolder", mediaRoots.everythingFolder());
        return "settings/index";
    }

    @PostMapping("/settings/roots")
    public String addRoot(@RequestParam(name = "path") String path, RedirectAttributes redirect) {
        try {
            mediaRoots.add(path);
            redirect.addFlashAttribute("notice", "Folder added. It is scanned within a minute, or press Scan now.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/roots/delete")
    public String removeRoot(@RequestParam(name = "path") String path, RedirectAttributes redirect) {
        try {
            mediaRoots.remove(path);
            redirect.addFlashAttribute("notice", "Folder removed and its collected titles cleared.");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/settings";
    }

    @PostMapping("/settings/scan")
    public String scanNow(RedirectAttributes redirect) {
        // Runs in the background: a first scan of a large drive can take a while.
        CompletableFuture.runAsync(scanner::scan);
        redirect.addFlashAttribute("notice", "Scan started. New titles appear in My media as they are found.");
        return "redirect:/settings";
    }
}
