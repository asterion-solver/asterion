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

using System.Globalization;
using System.Windows.Input;
using Asterion.Nina.Core;

namespace Asterion.Nina;

/// <summary>A catalog in the settings, with what can be done with it now.</summary>
public sealed class CatalogItem {
    public CatalogItem(Catalog catalog, bool busy, Func<Catalog, Task> download, Action<Catalog> remove) {
        Name = catalog.Name;
        Description = catalog.Description;
        Installed = catalog.Installed;
        CanDownload = !catalog.Installed && !busy;
        CanRemove = catalog.Installed && !busy;
        Download = new Command(() => _ = download(catalog));
        Remove = new Command(() => remove(catalog));
        State = catalog.Installed ? $"installed, {Size(catalog.Size)}" : "not installed";
    }

    public string Name { get; }
    public string Description { get; }
    public bool Installed { get; }
    public string State { get; }
    public bool CanDownload { get; }
    public bool CanRemove { get; }
    public ICommand Download { get; }
    public ICommand Remove { get; }

    private static string Size(long bytes) => bytes >= 1L << 30
        ? string.Format(CultureInfo.CurrentCulture, "{0:0.0} GB", bytes / (double) (1L << 30))
        : string.Format(CultureInfo.CurrentCulture, "{0:0} MB", bytes / (double) (1L << 20));
}
