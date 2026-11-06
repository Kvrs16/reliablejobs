package dev.reliablejobs.auth;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByUsername(String username);
}
