/*
 * Copyright 2026 CAN Talk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.talkback.actor.gemini.local

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTest {
  @Test
  fun everyDownloadUsesAnImmutableRevisionAndVerificationMetadata() {
    for (model in LocalModel.entries) {
      assertTrue(model.id, model.revision.matches(Regex("[0-9a-f]{40}")))
      assertTrue(model.id, model.sha256.matches(Regex("[0-9a-f]{64}")))
      assertTrue(model.id, model.sizeBytes > 0)
      val url = URI(model.url)
      assertEquals(model.id, "https", url.scheme)
      assertEquals(model.id, "huggingface.co", url.host)
      assertEquals(
        model.id,
        "/${model.repo}/resolve/${model.revision}/${model.fileName}",
        url.path,
      )
    }
  }
}
