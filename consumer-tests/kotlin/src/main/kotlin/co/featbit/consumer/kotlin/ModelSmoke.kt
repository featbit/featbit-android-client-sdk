package co.featbit.consumer.kotlin

import co.featbit.android.api.*
import co.featbit.android.datasource.*

object ModelSmoke {
    fun verify() {
        val user = User.builder("kotlin-user")
            .name("Kotlin User")
            .attribute("plan", AttributeValue.text("test").value!!)
            .build().value!!
        check(user.attributes["plan"]?.kind == AttributeValue.Kind.TEXT)
        check(user.attributes["plan"]?.text == "test")
        val options = ClientOptions.builder().user(user).offline(true).bootstrap(emptyList()).build().value!!
        check(options.bootstrap != null && options.bootstrap!!.isEmpty())
        val array = FbValue.ofArray(listOf(FbValue.jsonNull(), FbValue.ofBoolean(true))).value!!
        check(array.asArray()!![0].kind == FbValue.Kind.NULL)
        check(!FbValue.ofNumber(Double.NaN).isSuccess)
        check(SdkInfo.getVersion() == "0.1.0-SNAPSHOT")
        check(FullUpdate.create(emptyList()).isSuccess)
        check(LocalFactory().capabilities().provenance == Provenance.LOCAL)
    }
    class LocalFactory : DataSourceFactory {
        override fun capabilities() = SourceCapabilities(Provenance.LOCAL, false)
        override fun validate() = Outcome.success(SourceValidation.VALID)
        override fun create(context: SourceSessionContext, sink: SourceUpdateSink): Outcome<DataSource> = Outcome.success(object : DataSource {
            override fun start(completion: Completion<SourceStarted>) {
                sink.submit(FullUpdate.create(emptyList()).value!!)
                completion.onComplete(Outcome.success(SourceStarted.STARTED))
            }
            override fun stop(completion: Completion<SourceStopped>) { completion.onComplete(Outcome.success(SourceStopped.STOPPED)) }
        })
    }
}
