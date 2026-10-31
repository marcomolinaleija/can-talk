/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.actor.gemini;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.accessibility.talkback.actor.gemini.DataFieldUtils.GeminiResponse;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Performs image description REST requests with the selected cloud provider. */
public class GeminiRestRequestPerformer {
  private static final String TAG = "GeminiRequest";
  private static final String API_KEY_HEADER = "x-goog-api-key";
  private static final int TIMEOUT_MS = 30_000;
  // Retrying a request that timed out sends and pays for the same slow request again.
  private static final int MAX_RETRIES = 0;
  private static final long MAX_GROQ_RETRY_WAIT_MS = 20_000;
  private static final int MAX_GROQ_RATE_RETRIES = 2;

  interface GeminiRestResponseCallback {
    void onResponse(GeminiResponse response);

    void onFailure(GeminiFailure failure);

    void onCancelled();
  }

  private final RequestQueue requestQueue;
  private final SharedPreferences prefs;
  private JsonObjectRequest pendingRequest;
  private GeminiRestResponseCallback pendingCallback;
  private long requestGeneration;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private Runnable pendingRetry;
  private GroqRateLimit lastGroqLimit;
  private long groqAllowedAt;
  private String rateLimitKey = "";

  public GeminiRestRequestPerformer(Context context) {
    this(Volley.newRequestQueue(context), SharedPreferencesUtils.getSharedPreferences(context));
  }

  GeminiRestRequestPerformer(RequestQueue requestQueue, SharedPreferences prefs) {
    this.requestQueue = requestQueue;
    this.prefs = prefs;
  }

  /** A missing Groq key is reported by the transport with instructions for that provider. */
  public boolean isSupported(String geminiKey) {
    return GroqSettings.isSelected(prefs)
        || !TextUtils.isEmpty(geminiKey)
        || isKeylessInitialized();
  }

  public boolean isKeylessInitialized() {
    return false;
  }

  /**
   * Performs an HTTP POST request to the given URL with the specified message body. The API key, if
   * not empty, goes in a header rather than the URL, because Volley and the system log URLs.
   */
  public void performRequest(
      String url,
      String apiKey,
      JSONObject postData,
      GeminiRestResponseCallback unloggedCallback) {
    cancelExistingRequestIfNeeded();
    final long generation = ++requestGeneration;
    GeminiRestResponseCallback callback = loggingFailures(unloggedCallback);
    boolean groq = GroqSettings.isSelected(prefs);
    String requestKey = groq ? GroqSettings.userKey(prefs) : apiKey;
    if (groq && !requestKey.equals(rateLimitKey)) clearGroqRateLimit();
    JSONObject requestBody;
    try {
      requestBody = groq ? GroqProtocol.request(postData) : postData;
    } catch (JSONException e) {
      callback.onFailure(GeminiFailure.other("Could not prepare the image request").forGroq());
      return;
    }
    if (groq && TextUtils.isEmpty(requestKey)) {
      callback.onFailure(GeminiFailure.fromResponse(401, null).forGroq());
      return;
    }
    CloudRequest request = new CloudRequest(
        generation, groq, groq ? GroqSettings.URL : url, requestKey, requestBody, callback);
    pendingCallback = callback;
    long remainingWait = groqAllowedAt - SystemClock.elapsedRealtime();
    if (groq && requestKey.equals(rateLimitKey) && lastGroqLimit != null && remainingWait > 0) {
      // Deliver asynchronously: the caller starts progress after performRequest returns.
      pendingRetry = () -> {
        pendingRetry = null;
        request.fail(GeminiFailure.groqRateLimit(lastGroqLimit.withWait(remainingWait)));
      };
      mainHandler.post(pendingRetry);
    } else {
      request.send();
    }
  }

  private final class CloudRequest {
    private final long generation;
    private final boolean groq;
    private final String url;
    private final String key;
    private final JSONObject body;
    private final GeminiRestResponseCallback callback;
    private final long startedAt = SystemClock.elapsedRealtime();
    private int retries;

    CloudRequest(long generation, boolean groq, String url, String key, JSONObject body,
        GeminiRestResponseCallback callback) {
      this.generation = generation;
      this.groq = groq;
      this.url = url;
      this.key = key;
      this.body = body;
      this.callback = callback;
    }

    void send() {
      if (generation != requestGeneration) return;
      long remainingTime = TIMEOUT_MS - (SystemClock.elapsedRealtime() - startedAt);
      if (remainingTime <= 0) {
        GeminiFailure failure = GeminiFailure.fromVolleyError(new com.android.volley.TimeoutError());
        fail(groq ? failure.forGroq() : failure);
        return;
      }
      JsonObjectRequest stringRequest = new JsonObjectRequest(
            Request.Method.POST,
            url,
            body,
            response -> {
              if (generation != requestGeneration) {
                return;
              }
              pendingRequest = null;
              pendingCallback = null;
              if (groq) {
                clearGroqRateLimit();
              }
              try {
                GeminiResponse result = groq
                    ? GroqProtocol.response(response)
                    : DataFieldUtils.parseGeminiResponse(response);
                if (groq && response.optJSONObject("usage") != null) {
                  JSONObject usage = response.optJSONObject("usage");
                  // Counters only: never log the image, prompt, answer or organization.
                  Log.i("GroqRequest", "Description completed: inputTokens="
                      + usage.optLong("prompt_tokens", -1) + ", outputTokens="
                      + usage.optLong("completion_tokens", -1));
                }
                callback.onResponse(result);
              } catch (JSONException e) {
                GeminiFailure failure = GeminiFailure.other("Could not read the answer");
                callback.onFailure(groq ? failure.forGroq() : failure);
              }
            },
            this::onError) {
          @Override
          public Map<String, String> getHeaders() throws AuthFailureError {
            if (TextUtils.isEmpty(key)) {
              return super.getHeaders();
            }
            Map<String, String> headers = new HashMap<>(super.getHeaders());
            headers.put(groq ? "Authorization" : API_KEY_HEADER,
                groq ? "Bearer " + key : key);
            return headers;
          }
        };

      stringRequest.setRetryPolicy(new DefaultRetryPolicy(
          (int) Math.min(TIMEOUT_MS, remainingTime), MAX_RETRIES,
          DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
      // Each description is private and current; do not cache image responses on disk.
      stringRequest.setShouldCache(false);
      pendingRequest = stringRequest;
      requestQueue.add(stringRequest);
    }

    private void onError(VolleyError error) {
      if (generation != requestGeneration) return;
      pendingRequest = null;
      GeminiFailure failure = groq
          ? GeminiFailure.fromGroqVolleyError(error) : GeminiFailure.fromVolleyError(error);
      GroqRateLimit limit = failure.getGroqLimit();
      if (groq && limit != null && !limit.getRequestTooLarge()) {
        // Use a bounded exponential backoff only when Groq supplies no useful wait.
        // An explicit server wait is never shortened to fit our retry policy.
        long wait = limit.getRetryAfterMillis() != null
            ? limit.getRetryAfterMillis() : 2_000L << retries;
        lastGroqLimit = limit;
        rateLimitKey = key;
        groqAllowedAt = SystemClock.elapsedRealtime() + wait;
        long remainingTime = TIMEOUT_MS - (SystemClock.elapsedRealtime() - startedAt);
        if (retries < MAX_GROQ_RATE_RETRIES && limit.getWindow() != GroqRateLimit.Window.DAILY
            && limit.getWindow() != GroqRateLimit.Window.ACCOUNT
            && wait <= MAX_GROQ_RETRY_WAIT_MS && remainingTime > wait + 1_000) {
          retries++;
          // A 429 rejected the request. Retry at most twice, within the original
          // 30-second budget; never retry an accepted request that timed out.
          Log.i("GroqRateLimit", "Retry " + retries + " in " + wait + " ms: " + limit);
          pendingRetry = () -> {
            pendingRetry = null;
            if (generation != requestGeneration) return;
            if (!GroqSettings.isSelected(prefs) || !key.equals(GroqSettings.userKey(prefs))) {
              clearGroqRateLimit();
              cancelExistingRequestIfNeeded();
              return;
            }
            send();
          };
          mainHandler.postDelayed(pendingRetry, wait);
          return;
        }
      }
      fail(failure);
    }

    private void fail(GeminiFailure failure) {
      if (generation != requestGeneration) return;
      pendingRequest = null;
      pendingCallback = null;
      callback.onFailure(failure);
    }
  }

  private void clearGroqRateLimit() {
    lastGroqLimit = null;
    groqAllowedAt = 0;
    rateLimitKey = "";
  }

  /**
   * Wraps the callback so that each failure is logged at error level, which is logged at every log
   * output level.
   */
  static GeminiRestResponseCallback loggingFailures(GeminiRestResponseCallback callback) {
    return new GeminiRestResponseCallback() {
      @Override
      public void onResponse(GeminiResponse response) {
        callback.onResponse(response);
      }

      @Override
      public void onFailure(GeminiFailure failure) {
        LogUtils.e(TAG, "Image description request failed: %s", failure.getLogMessage());
        callback.onFailure(failure);
      }

      @Override
      public void onCancelled() {
        callback.onCancelled();
      }
    };
  }

  public void cancelExistingRequestIfNeeded() {
    requestGeneration++;
    if (pendingRetry != null) {
      mainHandler.removeCallbacks(pendingRetry);
      pendingRetry = null;
    }
    if (pendingRequest != null) {
      pendingRequest.cancel();
      pendingRequest = null;
    }
    GeminiRestResponseCallback callback = pendingCallback;
    pendingCallback = null;
    if (callback != null) callback.onCancelled();
  }

  public boolean hasPendingTransaction() {
    return pendingCallback != null;
  }
}
