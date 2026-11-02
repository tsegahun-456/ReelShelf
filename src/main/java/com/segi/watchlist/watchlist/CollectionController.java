package com.segi.watchlist.watchlist;

import com.segi.watchlist.library.LocalMedia;
import com.segi.watchlist.library.LocalMediaRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import com.segi.watchlist.catalog.MovieCatalogService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.RestClientException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class CollectionController {
    private final UserCollectionService service;
    private final LocalMediaRepository localMedia;

    public CollectionController(UserCollectionService service, LocalMediaRepository localMedia) {
        this.service = service;
        this.localMedia = localMedia;
    }

    @GetMapping("/collections")
    public String index(Model model, Authentication authentication) {
        header(model, authentication, "watchlist");
        model.addAttribute("collections", service.list(authentication.getName()));
        return "collections/index";
    }

    @GetMapping("/collections/{id}")
    public String show(@PathVariable long id,
                       @RequestParam(defaultValue = "") String year,
                       @RequestParam(defaultValue = "") String genre,
                       @RequestParam(defaultValue = "ANY") String local,
                       Model model, Authentication authentication) {
        header(model, authentication, "watchlist");
        UserCollection collection = service.requireOwned(id, authentication.getName());
        List<UserCollectionItem> all = service.items(id, authentication.getName());
        Set<String> localTitles = localMedia.findAllByOrderByDisplayTitleAsc().stream()
                .filter(media -> media.getTmdbId() != null)
                .map(media -> media.getTmdbMediaType() + ":" + media.getTmdbId())
                .collect(Collectors.toSet());
        String normalizedLocal = Set.of("AVAILABLE", "MISSING").contains(local) ? local : "ANY";
        List<CollectionItemView> filtered = all.stream()
                .map(item -> new CollectionItemView(item, localTitles.contains(item.getMediaType() + ":" + item.getTmdbId())))
                .filter(item -> year.isBlank() || item.item().getYear().equals(year))
                .filter(item -> genre.isBlank() || item.item().getGenreNames().lines()
                        .flatMap(line -> java.util.Arrays.stream(line.split("\\|")))
                        .anyMatch(value -> value.equalsIgnoreCase(genre)))
                .filter(item -> normalizedLocal.equals("ANY")
                        || normalizedLocal.equals("AVAILABLE") == item.localAvailable())
                .toList();
        List<String> genres = all.stream().flatMap(item -> java.util.Arrays.stream(item.getGenreNames().split("\\|")))
                .filter(value -> !value.isBlank()).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<String> years = all.stream().map(UserCollectionItem::getYear).filter(value -> !value.isBlank())
                .distinct().sorted(Comparator.reverseOrder()).toList();
        model.addAttribute("collection", collection);
        model.addAttribute("items", filtered);
        model.addAttribute("genreOptions", genres);
        model.addAttribute("yearOptions", years);
        model.addAttribute("selectedGenre", genre);
        model.addAttribute("selectedYear", year);
        model.addAttribute("selectedLocal", normalizedLocal);
        model.addAttribute("onlineProviderAvailable", false);
        return "collections/detail";
    }

    @PostMapping("/collections/create")
    public String create(@RequestParam String name, Authentication authentication, RedirectAttributes redirect) {
        try {
            UserCollection collection = service.create(authentication.getName(), name);
            return "redirect:/collections/" + collection.getId();
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/collections";
        }
    }

    @PostMapping("/collections/{id}/delete")
    public String delete(@PathVariable long id, Authentication authentication, RedirectAttributes redirect) {
        service.delete(authentication.getName(), id);
        redirect.addFlashAttribute("notice", "Collection deleted.");
        return "redirect:/collections";
    }

    @PostMapping("/collections/{id}/items/add")
    public String addTitle(@PathVariable long id, @RequestParam long movieId,
                           @RequestParam(defaultValue = "MOVIE") String mediaType,
                           Authentication authentication, RedirectAttributes redirect) {
        try {
            service.addTitle(authentication.getName(), id, movieId, mediaType);
            redirect.addFlashAttribute("notice", "Added to your collection.");
        } catch (MovieCatalogService.TmdbNotConfiguredException | RestClientException | IllegalStateException exception) {
            redirect.addFlashAttribute("notice", "Could not add this title right now.");
        }
        return "redirect:/collections/" + id;
    }

    @PostMapping("/collections/{id}/items/{itemId}/remove")
    public String removeTitle(@PathVariable long id, @PathVariable long itemId, Authentication authentication) {
        service.removeItem(authentication.getName(), id, itemId);
        return "redirect:/collections/" + id;
    }

    private void header(Model model, Authentication authentication, String activePage) {
        model.addAttribute("activePage", activePage);
        model.addAttribute("signedIn", true);
        model.addAttribute("currentEmail", authentication.getName());
    }

    public record CollectionItemView(UserCollectionItem item, boolean localAvailable) { }
}
