package org.opendcs.fixtures.configurations.opendcs.oracle;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.commons.io.FileUtils;
import org.jdbi.v3.core.Jdbi;
import org.opendcs.database.DatabaseService;
import org.opendcs.database.MigrationManager;
import org.opendcs.database.SimpleDataSource;
import org.opendcs.database.api.OpenDcsDatabase;
import org.opendcs.database.impl.opendcs.OpenDcsOracleProvider;
import org.opendcs.fixtures.UserPropertiesBuilder;
import org.opendcs.fixtures.spi.Configuration;
import org.opendcs.spi.database.MigrationProvider;
import org.testcontainers.oracle.OracleContainer;

import decodes.db.Database;
import decodes.launcher.Profile;
import decodes.sql.OracleSequenceKeyGenerator;
import decodes.tsdb.ComputationApp;
import decodes.tsdb.TimeSeriesDb;
import decodes.tsdb.TsdbAppTemplate;
import decodes.util.DecodesSettings;
import ilex.util.FileLogger;
import opendcs.dao.CompDependsDAO;
import opendcs.dao.DaoBase;
import opendcs.dao.LoadingAppDao;
import opendcs.dao.XmitRecordDAO;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.security.SystemExit;

/**
 * Handles setup of an OpenDCS Oracle SQL Database instance.
 *
 */
public class OpenDCSOracleConfiguration implements Configuration
{
    private static Logger log = Logger.getLogger(OpenDCSOracleConfiguration.class.getName());

    public static final String NAME = "OpenDCS-Oracle";

    private static OracleContainer db = null;
    private File userDir;
    private File propertiesFile;
    private static AtomicBoolean started = new AtomicBoolean(false);
    private HashMap<Object,Object> environmentVars = new HashMap<>();
    private Profile profile = null;
    private OpenDcsDatabase databases = null;

    // FUTURE work: allow passing of override values to bypass the test container creation
    // ... OR setup a separate testcontainer library like USACE did for CWMS.
    private static final String DATABASE_NAME = "FREEPDB1";
    private static final String SCHEMA_OWNING_USER = "otsdb_adm";
    private static final String SCHEMA_OWNING_USER_PASSWORD = "dcs_owner_password";
    private static final String DCS_ADMIN_USER = "dcs_admin";
    private static final String DCS_ADMIN_USER_PASSWORD = "dcs_admin_password";

    public OpenDCSOracleConfiguration(File userDir) throws Exception
    {
        this.userDir = userDir;
        this.propertiesFile = new File(userDir,"/user.properties");
    }

    @Override
    public File getPropertiesFile()
    {
        return this.propertiesFile;
    }

    @Override
    public File getUserDir()
    {
        return this.userDir;
    }

    @Override
    public boolean isSql()
    {
        return true;
    }

    @Override
    public boolean isTsdb()
    {
        return true;
    }

    @Override
    public Map<Object,Object> getEnvironment()
    {
        return this.environmentVars;
    }

    /**
     * Actually setup the database
     * @throws Exception
    */
    private void installDb(SystemExit exit,EnvironmentVariables environment, SystemProperties properties, UserPropertiesBuilder configBuilder) throws Exception
    {
        // These should always be set.
        environmentVars.put("DB_USERNAME",DCS_ADMIN_USER);
        environmentVars.put("DB_PASSWORD",DCS_ADMIN_USER_PASSWORD);
        environment.set("DB_USERNAME",DCS_ADMIN_USER);
        environment.set("DB_PASSWORD",DCS_ADMIN_USER_PASSWORD);
        if (isRunning())
        {
            return;
        }
        if(db == null)
        {
            db = new OracleContainer("gvenzl/oracle-free:full-faststart")
                    .withUsername(SCHEMA_OWNING_USER)
                    .withPassword(SCHEMA_OWNING_USER_PASSWORD)
                    .withStartupTimeoutSeconds(300)
                    ;
        }

        db.start();
        environmentVars.put("DB_URL", db.getJdbcUrl());
        environment.set("DB_URL", db.getJdbcUrl());
        schemaSetup(db, db.getUsername());
        createPropertiesFile(configBuilder, this.propertiesFile);
        profile = Profile.getProfile(this.propertiesFile);
        DataSource ds = new SimpleDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());

        MigrationManager mm = new MigrationManager(ds,OpenDcsOracleProvider.NAME);
        MigrationProvider mp = mm.getMigrationProvider();
        mp.setPlaceholderValue("NUM_TS_TABLES", "1");
        mp.setPlaceholderValue("NUM_TEXT_TABLES","1");
        mp.setPlaceholderValue("TSDB_ADM_SCHEMA",SCHEMA_OWNING_USER);
        mp.setPlaceholderValue("TSDB_ADM_PASSWORD",SCHEMA_OWNING_USER_PASSWORD);
        mp.setPlaceholderValue("TABLE_SPACE_SPEC","");
        mm.migrate();
        Jdbi jdbi = mm.getJdbiHandle();
        log.info("Creating application user.");
        List<String> roles = new ArrayList<>();
        roles.add("OTSDB_ADMIN");
        roles.add("OTSDB_MGR");
        roles.add("OTSDB_COMP_EXEC");
        mp.createUser(jdbi, DCS_ADMIN_USER, DCS_ADMIN_USER_PASSWORD, roles);
        log.info("Setting authentication environment vars.");
        ilex.util.Logger originalLog = ilex.util.Logger.instance();
        ilex.util.FileLogger fl = null;
        try
        {
            fl = new FileLogger("test", new File(userDir,"baseline-import.log").getAbsolutePath(), 200*1024*1024);
            fl.setMinLogPriority(ilex.util.Logger.E_DEBUG3);
            ilex.util.Logger.setLogger(fl);
            mp.loadBaselineData(profile, DCS_ADMIN_USER, DCS_ADMIN_USER_PASSWORD);
        }
        finally
        {
            if (fl != null)
            {
                ilex.util.Logger.setLogger(originalLog);
                fl.close();
            }
        }
        setStarted();
    }

    @Override
    public void start(SystemExit exit, EnvironmentVariables environment, SystemProperties properties) throws Exception
    {
        File editDb = new File(userDir,"edit-db");
        new File(userDir,"output").mkdir();
        editDb.mkdirs();
        UserPropertiesBuilder configBuilder = new UserPropertiesBuilder();
        // set username/pw (env)
        String stageDir = System.getProperty("DCSTOOL_HOME");
        FileUtils.copyDirectory(new File(stageDir+"/edit-db"),editDb);
        FileUtils.copyDirectory(new File(stageDir+"/schema"),new File(userDir,"/schema/"));
        installDb(exit, environment, properties, configBuilder);
        createPropertiesFile(configBuilder, this.propertiesFile);
    }

    private void createPropertiesFile(UserPropertiesBuilder configBuilder, File propertiesFile) throws Exception
    {
        try (OutputStream out = new FileOutputStream(propertiesFile);)
        {
            configBuilder.withDatabaseLocation(db.getJdbcUrl());
            configBuilder.withEditDatabaseType("OPENTSDB");
            configBuilder.withDatabaseDriver("org.postgresql.Driver");
            configBuilder.withSiteNameTypePreference("CWMS");
            configBuilder.withSqlKeyGenerator(OracleSequenceKeyGenerator.class);
            configBuilder.withDecodesAuth("env-auth-source:username=DB_USERNAME,password=DB_PASSWORD");
            configBuilder.build(out);
        }
    }

    private void setStarted()
    {
        synchronized(started)
        {
            started.set(true);
        }
    }

    @Override
    public boolean isRunning()
    {
        synchronized(started)
        {
            return started.get();
        }
    }

    @Override
    public TimeSeriesDb getTsdb() throws Throwable
    {
        return getOpenDcsDatabase().getLegacyDatabase(TimeSeriesDb.class).get();
    }

    @Override
    public Database getDecodesDatabase() throws Throwable
    {
        return getOpenDcsDatabase().getLegacyDatabase(Database.class).get();
    }

    private void buildDatabases() throws Exception
    {
        Properties credentials = new Properties();
        credentials.put("username",DCS_ADMIN_USER);
        credentials.put("password",DCS_ADMIN_USER_PASSWORD);
        databases = DatabaseService.getDatabaseFor("utility", DecodesSettings.fromProfile(profile), credentials);
    }

    @Override
    public boolean implementsSupportFor(Class<? extends TsdbAppTemplate> appClass)
    {
        Objects.requireNonNull(appClass, "You must specify a valid class, not null.");
        if (appClass.equals(ComputationApp.class))
        {
            return true;
        } // add more cases here.
        return false;
    }

    /**
     * Returns true if this Database implementation supports a given dataset.
     * @param dao Class that extends from {@link opendcs.dao.DaoBase}
     */
    @Override
    public boolean supportsDao(Class<? extends DaoBase> dao)
    {
        Objects.requireNonNull(dao, "You must specifiy a valid class, not null.");
        /**
         * Extends this list as specific tests and requirements are added in the future.
         */
        if (dao.equals(XmitRecordDAO.class))
        {
            return true;
        }
        else if(dao.equals(CompDependsDAO.class))
        {
            return true;
        }
        else if(dao.equals(LoadingAppDao.class))
        {
            return true;
        }
        return false;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    private static void schemaSetup(OracleContainer db, String user) throws SQLException
    {
        Driver driverInstance;
        try
        {
            driverInstance = (Driver)Class.forName(db.getDriverClassName()).newInstance();
        }
        catch (InstantiationException | IllegalAccessException | ClassNotFoundException ex)
        {
            throw new SQLException("Unable to initialize schema user.", ex);
        }

        Properties info = new Properties();
        info.put("user", "sys");        
        info.put("internal_logon","sysdba");
        info.put("password", db.getPassword());
        try (Connection conn =  driverInstance.connect(db.getJdbcUrl(),info);
             Statement stmt = conn.createStatement();)
        {
            stmt.executeQuery("GRANT ALTER ANY TABLE,CREATE ANY TABLE,CREATE ANY INDEX,CREATE ANY SEQUENCE,"
                            + "CREATE ANY VIEW,CREATE ANY PROCEDURE,CREATE ANY TRIGGER,CREATE ANY JOB,"
                            + "CREATE ANY SYNONYM,DROP ANY SYNONYM,CREATE PUBLIC SYNONYM,DROP PUBLIC SYNONYM,"
                            + "CREATE ROLE, CREATE USER"
                            + " TO " + user);
            stmt.executeQuery("GRANT CREATE SESSION,RESOURCE,CONNECT"
                            + " TO " + user + " WITH ADMIN OPTION");
            stmt.executeQuery("ALTER USER " + user + " DEFAULT ROLE ALL");
        }
    }

    @Override
    public OpenDcsDatabase getOpenDcsDatabase() throws Throwable
    {
        synchronized(this)
        {
            if (databases == null)
            {
                buildDatabases();
            }
            return databases;
        }
    }
}
