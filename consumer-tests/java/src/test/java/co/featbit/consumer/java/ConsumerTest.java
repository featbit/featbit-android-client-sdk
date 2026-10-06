package co.featbit.consumer.java;

public class ConsumerTest {
    @org.junit.Test
    public void publishedAarExposesIdentityOperationsOnClient() throws Exception {
        Class<?> capability = co.featbit.android.api.FeatBitClient.class;
        org.junit.Assert.assertEquals(
                co.featbit.android.api.Operation.class,
                capability
                        .getMethod("identifyContext", co.featbit.android.api.User.class, long.class)
                        .getReturnType());
        org.junit.Assert.assertEquals(
                co.featbit.android.api.Operation.class,
                capability.getMethod("identifyAnonymousContext", long.class).getReturnType());
        org.junit.Assert.assertEquals(
                long.class,
                co.featbit.android.api.IdentityReceipt.class
                        .getMethod("getGeneration")
                        .getReturnType());
    }

    @org.junit.Test
    public void publishedAarModelsWork() {
        ModelSmoke.verify();
    }
}
