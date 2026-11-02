package com.segi.watchlist.watchlist;

import com.segi.watchlist.account.Account;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_collection", uniqueConstraints = @UniqueConstraint(
        name = "user_collection_user_name_unique", columnNames = {"user_id", "name"}))
public class UserCollection {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Account account;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserCollection() { }

    public UserCollection(Account account, String name) {
        this.account = account;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
