package dev.atalho.api;

import dev.atalho.api.auth.RefreshToken;
import dev.atalho.api.auth.RefreshTokenRepository;
import dev.atalho.api.user.Role;
import dev.atalho.api.user.User;
import dev.atalho.api.user.UserRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the Flyway migrations against a real Postgres. Hibernate's {@code ddl-auto=validate}
 * makes the context fail to start if the entities and the schema drift apart.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrationIT {

    @Autowired
    Flyway flyway;

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        refreshTokens.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    @Test
    void appliesAllMigrationsSuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied)
                .extracting(info -> info.getVersion().getVersion())
                .contains("1", "2");
        assertThat(Arrays.stream(applied).map(MigrationInfo::getState))
                .allMatch(state -> state == MigrationState.SUCCESS);
    }

    @Test
    void emailIsNormalizedAndUniqueIgnoringCase() {
        User saved = users.saveAndFlush(new User("  Ana@Example.com ", "hash", Role.USER));

        assertThat(saved.getEmail()).isEqualTo("ana@example.com");
        assertThat(users.findByEmail(User.normalizeEmail("ANA@EXAMPLE.COM"))).isPresent();
        assertThatThrownBy(() -> users.saveAndFlush(new User("ANA@example.com", "hash", Role.USER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsEmailThatSkippedNormalization() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (email, password_hash) VALUES ('Raw@Example.com', 'hash')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserDeletesTheirRefreshTokens() {
        User user = users.saveAndFlush(new User("bia@example.com", "hash", Role.USER));
        refreshTokens.saveAndFlush(new RefreshToken(user, "token-hash", Instant.now().plus(7, ChronoUnit.DAYS)));

        users.deleteById(user.getId());
        users.flush();

        assertThat(refreshTokens.count()).isZero();
    }
}
