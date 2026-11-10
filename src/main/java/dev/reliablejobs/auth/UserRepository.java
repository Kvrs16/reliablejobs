package dev.reliablejobs.auth;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByUsername(String username);

  @Transactional
  @Modifying(clearAutomatically = true)
  @Query("update AppUser u set u.passwordHash = :hash where u.username = :username")
  int resetPasswordHash(String username, String hash);
}
