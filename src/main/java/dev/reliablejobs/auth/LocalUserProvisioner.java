package dev.reliablejobs.auth;

import java.io.Console;
import java.util.Arrays;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Explicit, interactive administration command; never seeds a known password. */
@Component
@Profile("provision")
public class LocalUserProvisioner implements ApplicationRunner {
  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final Environment env;
  private final ConfigurableApplicationContext context;

  public LocalUserProvisioner(
      UserRepository users,
      PasswordEncoder encoder,
      Environment env,
      ConfigurableApplicationContext context) {
    this.users = users;
    this.encoder = encoder;
    this.env = env;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    String username = env.getRequiredProperty("provision.username");
    String role = env.getProperty("provision.role", "USER");
    if (!username.matches("[a-zA-Z0-9_.-]{3,64}") || !role.matches("USER|ADMIN"))
      throw new IllegalArgumentException("Invalid username or role");
    Console console = System.console();
    if (console == null)
      throw new IllegalStateException("Run the packaged jar in an interactive terminal");
    char[] secret = console.readPassword("New password (12-64 characters): ");
    try {
      if (secret == null
          || secret.length < 12
          || secret.length > 64
          || new String(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
        throw new IllegalArgumentException(
            "Password must be 12-64 characters and at most 72 UTF-8 bytes");
      users.saveAndFlush(new AppUser(username, encoder.encode(new String(secret)), role));
      console.printf("User created.%n");
    } finally {
      if (secret != null) Arrays.fill(secret, '\0');
    }
    context.close();
  }
}
