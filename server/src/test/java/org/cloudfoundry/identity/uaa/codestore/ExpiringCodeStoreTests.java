package org.cloudfoundry.identity.uaa.codestore;

import org.cloudfoundry.identity.uaa.test.JdbcTestBase;
import org.cloudfoundry.identity.uaa.util.TimeService;
import org.cloudfoundry.identity.uaa.util.TimeServiceImpl;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public abstract class ExpiringCodeStoreTests extends JdbcTestBase {

    ExpiringCodeStore expiringCodeStore;
    TimeService mockTimeService;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mockTimeService = mock(TimeServiceImpl.class);
    }

    public int countCodes() {
        if (expiringCodeStore instanceof InMemoryExpiringCodeStore) {
            Map map = (Map) ReflectionTestUtils.getField(expiringCodeStore, "store");
            return map.size();
        } else {
            // confirm that everything is clean prior to test.
            return jdbcTemplate.queryForObject("select count(*) from expiring_code_store", Integer.class);
        }
    }

    @Test
    public void testGenerateCode() {
        String data = "{}";
        Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + 60000);
        ExpiringCode expiringCode = expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());

        Assert.assertNotNull(expiringCode);

        Assert.assertNotNull(expiringCode.getCode());
        Assert.assertTrue(expiringCode.getCode().trim().length() > 0);

        Assert.assertEquals(expiresAt, expiringCode.getExpiresAt());

        Assert.assertEquals(data, expiringCode.getData());
    }

    @Test(expected = NullPointerException.class)
    public void testGenerateCodeWithNullData() {
        String data = null;
        Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + 60000);
        expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());
    }

    @Test(expected = NullPointerException.class)
    public void testGenerateCodeWithNullExpiresAt() {
        String data = "{}";
        Timestamp expiresAt = null;
        expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGenerateCodeWithExpiresAtInThePast() {
        long now = 100000L;
        when(mockTimeService.getCurrentTimeMillis()).thenReturn(now);
        String data = "{}";
        Timestamp expiresAt = new Timestamp(now - 60000);
        expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());
    }

    @Test(expected = DataIntegrityViolationException.class)
    public void testGenerateCodeWithDuplicateCode() {
        RandomValueStringGenerator generator = mock(RandomValueStringGenerator.class);
        Mockito.when(generator.generate()).thenReturn("duplicate");
        expiringCodeStore.setGenerator(generator);

        String data = "{}";
        Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + 60000);
        expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());
        expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());
    }

    @Test
    public void testRetrieveCode() {
        String data = "{}";
        Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + 60000);
        ExpiringCode generatedCode = expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());

        ExpiringCode retrievedCode = expiringCodeStore.retrieveCode(generatedCode.getCode(), IdentityZoneHolder.get().getId());

        Assert.assertEquals(generatedCode, retrievedCode);

        Assert.assertNull(expiringCodeStore.retrieveCode(generatedCode.getCode(), IdentityZoneHolder.get().getId()));
    }

    @Test
    public void testRetrieveCode_In_Another_Zone() {
        String data = "{}";
        Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + 60000);
        ExpiringCode generatedCode = expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());

        IdentityZoneHolder.set(MultitenancyFixture.identityZone("other", "other"));
        Assert.assertNull(expiringCodeStore.retrieveCode(generatedCode.getCode(), IdentityZoneHolder.get().getId()));

        IdentityZoneHolder.clear();
        ExpiringCode retrievedCode = expiringCodeStore.retrieveCode(generatedCode.getCode(), IdentityZoneHolder.get().getId());
        Assert.assertEquals(generatedCode, retrievedCode);


    }

    @Test
    public void testRetrieveCodeWithCodeNotFound() {
        ExpiringCode retrievedCode = expiringCodeStore.retrieveCode("unknown", IdentityZoneHolder.get().getId());

        Assert.assertNull(retrievedCode);
    }

    @Test(expected = NullPointerException.class)
    public void testRetrieveCodeWithNullCode() {
        expiringCodeStore.retrieveCode(null, IdentityZoneHolder.get().getId());
    }

    @Test
    public void testStoreLargeData() {
        char[] oneMb = new char[1024 * 1024];
        Arrays.fill(oneMb, 'a');
        String aaaString = new String(oneMb);
        ExpiringCode expiringCode = expiringCodeStore.generateCode(aaaString, new Timestamp(
                System.currentTimeMillis() + 60000), null, IdentityZoneHolder.get().getId());
        String code = expiringCode.getCode();
        ExpiringCode actualCode = expiringCodeStore.retrieveCode(code, IdentityZoneHolder.get().getId());
        Assert.assertEquals(expiringCode, actualCode);
    }

    @Test
    public void testExpiredCodeReturnsNull() {
        long generationTime = 100000L;
        when(mockTimeService.getCurrentTimeMillis()).thenReturn(generationTime);
        String data = "{}";
        Timestamp expiresAt = new Timestamp(generationTime);
        ExpiringCode generatedCode = expiringCodeStore.generateCode(data, expiresAt, null, IdentityZoneHolder.get().getId());

        long expirationTime = 200000L;
        when(mockTimeService.getCurrentTimeMillis()).thenReturn(expirationTime);
        ExpiringCode retrievedCode = expiringCodeStore.retrieveCode(generatedCode.getCode(), IdentityZoneHolder.get().getId());
        Assert.assertNull(retrievedCode);
    }

    @Test
    public void testExpireCodeByIntent() {
        ExpiringCode code = expiringCodeStore.generateCode("{}", new Timestamp(System.currentTimeMillis() + 60000), "Test Intent", IdentityZoneHolder.get().getId());

        Assert.assertEquals(1, countCodes());

        IdentityZoneHolder.set(MultitenancyFixture.identityZone("id", "id"));
        expiringCodeStore.expireByIntent("Test Intent", IdentityZoneHolder.get().getId());
        Assert.assertEquals(1, countCodes());

        IdentityZoneHolder.clear();
        expiringCodeStore.expireByIntent("Test Intent", IdentityZoneHolder.get().getId());
        ExpiringCode retrievedCode = expiringCodeStore.retrieveCode(code.getCode(), IdentityZoneHolder.get().getId());
        Assert.assertEquals(0, countCodes());
        Assert.assertNull(retrievedCode);
    }

}
