/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace Asterion.Nina.Core;

/// <summary>Asterion couldn't run, or answered something unexpected: the message tells why, for the user.</summary>
public class AsterionException : Exception {
    public AsterionException(string message) : base(message) {
    }

    public AsterionException(string message, Exception cause) : base(message, cause) {
    }
}

/// <summary>Asterion can't solve because no catalog is installed.</summary>
public sealed class NoCatalogException(string message) : AsterionException(message);
