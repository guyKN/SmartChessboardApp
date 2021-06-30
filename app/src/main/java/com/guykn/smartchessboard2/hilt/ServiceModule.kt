package com.guykn.smartchessboard2.hilt

import android.app.Service
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.coroutineScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.guykn.smartchessboard2.network.lichess.LichessApi
import com.guykn.smartchessboard2.network.oauth2.LICHESS_BASE_URL
import com.guykn.smartchessboard2.reflection.NullableTypAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

@Module
@InstallIn(ServiceComponent::class)
object ServiceModule {
    @ServiceScoped
    @Provides
    fun provideLifeCycleService(service: Service): LifecycleService {
        check(service is LifecycleService) { "All services must extend LifecycleService" }
        return service
    }

    @ServiceScoped
    @Provides
    fun provideLifecycle(lifecycleService: LifecycleService): Lifecycle {
        return lifecycleService.lifecycle
    }

    @ServiceScoped
    @Provides
    fun provideCoroutineScope(lifecycle: Lifecycle): CoroutineScope {
        return lifecycle.coroutineScope
    }

    @ServiceScoped
    @Provides
    fun provideGson(): Gson {
        return GsonBuilder()
            .registerTypeAdapterFactory(NullableTypAdapterFactory())
            .create()
    }

    @ServiceScoped
    @Provides
    fun provideHttpClient(): OkHttpClient {
        val interceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        return OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(interceptor)
            .build()
    }

    @ServiceScoped
    @Provides
    fun provideLichessApi(gson: Gson, httpClient: OkHttpClient): LichessApi {
        val retrofit: Retrofit = Retrofit.Builder()
            .baseUrl(LICHESS_BASE_URL)
            .client(httpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        return retrofit.create(LichessApi::class.java)
    }
}