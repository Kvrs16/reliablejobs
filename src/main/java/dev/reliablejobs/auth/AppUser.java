package dev.reliablejobs.auth;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class AppUser {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 64)
  private String username;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 16)
  private String role;

  protected AppUser() {}

  public AppUser(String username, String hash, String role) {
    this.id = UUID.randomUUID();
    this.username = username;
    this.passwordHash = hash;
    this.role = role;
  }

  public UUID getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getRole() {
    return role;
  }
}
