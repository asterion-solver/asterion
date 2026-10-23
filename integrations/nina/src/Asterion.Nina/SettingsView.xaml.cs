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

using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;

namespace Asterion.Nina;

/// <summary>The settings of the plugin: the executable, the catalogs, and their downloads.</summary>
public partial class SettingsView : UserControl {
    public SettingsView() {
        InitializeComponent();
    }

    private void BrowseCatalogDirectory(object sender, RoutedEventArgs e) {
        if (DataContext is not AsterionSettings settings) {
            return;
        }
        var dialog = new OpenFolderDialog { Title = "Select the directory of the catalogs" };
        if (dialog.ShowDialog() == true) {
            settings.CatalogDirectory = dialog.FolderName;
        }
    }

    private void BrowseExecutable(object sender, RoutedEventArgs e) {
        if (DataContext is not AsterionSettings settings) {
            return;
        }
        var dialog = new OpenFileDialog {
            Title = "Select the asterion executable",
            Filter = "asterion.exe|asterion.exe|Executables|*.exe",
            CheckFileExists = true
        };
        if (dialog.ShowDialog() == true) {
            settings.ExecutablePath = dialog.FileName;
        }
    }
}
