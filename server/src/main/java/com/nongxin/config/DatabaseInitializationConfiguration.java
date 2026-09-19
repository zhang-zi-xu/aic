package com.nongxin.config;

import com.nongxin.service.SchemaMigrationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** A single Boot-recognized initializer owns both fresh schema creation and backed-up legacy migration. */
@Configuration(proxyBeanMethods = false)
@Import(DatabaseInitializationDependencyConfigurer.class)
public class DatabaseInitializationConfiguration {
    @Bean
    public NongxinDatabaseInitializer dataSourceScriptDatabaseInitializer(DataSource source,
            @Value("${spring.datasource.url:}") String url,
            @Value("${nongxin.backup-dir:}") String backupDirectory) {
        // The auto-configured JdbcTemplate depends on this initializer. Construct its private template from
        // DataSource directly to avoid a dependency cycle and never borrow two connections at once.
        return new NongxinDatabaseInitializer(source,
                new SchemaMigrationService(new JdbcTemplate(source), url, backupDirectory));
    }

    @Bean
    public SchemaMigrationService schemaMigrationService(NongxinDatabaseInitializer initializer) {
        return initializer.migration;
    }

    public static final class NongxinDatabaseInitializer extends SqlDataSourceScriptDatabaseInitializer {
        private final SchemaMigrationService migration;
        private boolean initialized;

        private NongxinDatabaseInitializer(DataSource source, SchemaMigrationService migration) {
            super(source, new DatabaseInitializationSettings());
            this.migration = migration;
        }

        @Override
        public synchronized boolean initializeDatabase() {
            if (initialized) return false;
            migration.initializeApplicationDatabase();
            initialized = true;
            return true;
        }
    }
}
