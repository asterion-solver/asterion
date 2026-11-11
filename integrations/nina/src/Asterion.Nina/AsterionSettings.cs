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

using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;
using Asterion.Nina.Core;
using NINA.Core.Model;
using NINA.Core.MyMessageBox;
using NINA.Core.Utility;
using NINA.Core.Utility.Notification;
using NINA.Profile;
using NINA.Profile.Interfaces;
using NINA.WPF.Base.Interfaces.Mediator;

namespace Asterion.Nina;

/// <summary>
/// The settings of the plugin, kept in the profile of N.I.N.A.: the directory of the catalogs, empty for the default
/// one of Asterion, and the timeout of solves. It also shows the version of Asterion, manages the catalogs, and has the
/// solver which the solves share, with the native library of Asterion which the plugin includes. There is a single
/// instance, shared by the plugin and its solver, so that a download goes on whichever page shows it.
/// </summary>
public sealed class AsterionSettings : INotifyPropertyChanged {
    private const string CatalogDirectoryKey = "CatalogDirectory";
    private const string TimeoutKey = "TimeoutSeconds";
    private static readonly Lock SharedLock = new();
    private static AsterionSettings? shared;

    private readonly IProfileService profileService;
    private readonly IApplicationStatusMediator statusMediator;
    private readonly Lock solverLock = new();
    private SharedSolver? solver;
    private int refreshes;
    private string status = "";
    private bool statusIsError;
    private CatalogList? catalogList;
    private IReadOnlyList<CatalogItem> catalogs = [];
    private string catalogStatus = "";
    private CancellationTokenSource? download;
    private string downloadStatus = "";
    private double? downloadFraction;
    private int customDensity = 1500;

    private AsterionSettings(IProfileService profileService, IApplicationStatusMediator statusMediator) {
        this.profileService = profileService;
        this.statusMediator = statusMediator;
        profileService.ProfileChanged += (_, _) => {
            OnPropertyChanged(nameof(CatalogDirectory));
            OnPropertyChanged(nameof(TimeoutSeconds));
            _ = RefreshAsync();
        };
        CancelDownload = new Command(StopDownload);
        InstallCustomDensity = new Command(() => _ = DownloadAsync(new Catalog(GaiaDensity.Name(customDensity), "", false, 0)));
        Refresh = new Command(() => _ = RefreshAsync());
        _ = RefreshAsync();
    }

    /// <summary>The settings, created by whichever of the plugin and its solver is created first.</summary>
    public static AsterionSettings Shared(IProfileService profileService, IApplicationStatusMediator statusMediator) {
        lock (SharedLock) {
            return shared ??= new AsterionSettings(profileService, statusMediator);
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private PluginOptionsAccessor Options =>
        new(profileService, PluginOptionsAccessor.GetAssemblyGuid(typeof(AsterionSettings)) ?? Guid.Empty);

    /// <summary>The directory of the catalogs, or empty for the default one of Asterion.</summary>
    public string CatalogDirectory {
        get => Options.GetValueString(CatalogDirectoryKey, "");
        set {
            Options.SetValueString(CatalogDirectoryKey, value?.Trim() ?? "");
            OnPropertyChanged();
            _ = RefreshAsync();
        }
    }

    /// <summary>The time after which a solve is given up, in seconds.</summary>
    public int TimeoutSeconds {
        get => Options.GetValueInt32(TimeoutKey, 60);
        set {
            Options.SetValueInt32(TimeoutKey, Math.Clamp(value, 5, 600));
            OnPropertyChanged();
        }
    }

    /// <summary>The version of Asterion, or why it can't run.</summary>
    public string Status {
        get => status;
        private set => Set(ref status, value);
    }

    public bool StatusIsError {
        get => statusIsError;
        private set => Set(ref statusIsError, value);
    }

    /// <summary>The catalogs which can be downloaded, and the ones which are installed.</summary>
    public IReadOnlyList<CatalogItem> Catalogs {
        get => catalogs;
        private set => Set(ref catalogs, value);
    }

    /// <summary>Where the catalogs are, or that none is installed.</summary>
    public string CatalogStatus {
        get => catalogStatus;
        private set => Set(ref catalogStatus, value);
    }

    public bool Downloading => download != null;

    /// <summary>What the current download does.</summary>
    public string DownloadStatus {
        get => downloadStatus;
        private set => Set(ref downloadStatus, value);
    }

    /// <summary>The progress of the current step of the download, from 0 to 1.</summary>
    public double DownloadFraction => downloadFraction ?? 0;

    /// <summary>Whether the progress of the current step of the download isn't known.</summary>
    public bool DownloadIndeterminate => downloadFraction == null;

    public ICommand CancelDownload { get; }

    /// <summary>The density of the catalog of Gaia DR3 to install besides the listed ones, in stars per square degree.</summary>
    public int CustomDensity {
        get => customDensity;
        set {
            Set(ref customDensity, value);
            OnCustomDensityChanged();
        }
    }

    /// <summary>What installing the catalog of <see cref="CustomDensity"/> does, and what it takes.</summary>
    public string CustomDensityDescription =>
        GaiaDensity.Describe(customDensity, catalogList?.Catalogs.Where(c => c.Published).Select(c => c.Name) ?? []);

    public bool CanInstallCustomDensity => GaiaDensity.IsValid(customDensity) && !Downloading;

    public ICommand InstallCustomDensity { get; }

    public ICommand Refresh { get; }

    /// <summary>The directory of the plugin, where the native library of Asterion is.</summary>
    public static string PluginDirectory => Path.GetDirectoryName(typeof(AsterionSettings).Assembly.Location) ?? "";

    /// <summary>The directory of the catalogs, its environment variables expanded, or null for the default one.</summary>
    public string? CatalogDirectoryPath => string.IsNullOrWhiteSpace(CatalogDirectory) ? null : Environment.ExpandEnvironmentVariables(CatalogDirectory.Trim());

    /// <summary>
    /// The solver which the solves share, with the native library of the plugin, loaded the first time it's asked for.
    /// N.I.N.A.'s own directory provides the Visual C++ runtime that the library needs.
    /// </summary>
    /// <exception cref="AsterionException">if the library can't be loaded</exception>
    public SharedSolver Solver() {
        lock (solverLock) {
            return solver ??= new SharedSolver(AsterionLibrary.Load(Path.Combine(PluginDirectory, "libasterion.dll")));
        }
    }

    /// <summary>Asks Asterion for its version and its catalogs, and shows what it answers.</summary>
    public async Task RefreshAsync() {
        var refresh = Interlocked.Increment(ref refreshes);
        var directory = CatalogDirectoryPath;
        string status;
        CatalogList? list = null;
        try {
            (status, list) = await Task.Run(() => {
                var library = Solver().Library;
                return ($"Asterion Solver {library.Version}, included in the plugin.", library.Catalogs(directory));
            });
        } catch (AsterionException e) {
            status = e.Message;
        }
        // the answer of an older refresh, such as one with a previous directory
        if (refresh != Volatile.Read(ref refreshes)) {
            return;
        }
        Status = status;
        StatusIsError = list == null;
        catalogList = list;
        UpdateCatalogs();
    }

    private void OnCustomDensityChanged() {
        OnPropertyChanged(nameof(CustomDensityDescription));
        OnPropertyChanged(nameof(CanInstallCustomDensity));
    }

    private void UpdateCatalogs() {
        // the catalogs which are published, or a download which starts or stops, change what a density does
        OnCustomDensityChanged();
        var list = catalogList;
        Catalogs = list?.Catalogs.Select(c => new CatalogItem(c, Downloading, DownloadAsync, RemoveAsync)).ToList() ?? [];
        CatalogStatus = list == null ? ""
            : list.Catalogs.Any(c => c.Installed) ? $"The catalogs are in {list.Directory}."
            : "No catalog is installed: download one to solve images.";
    }

    /// <summary>Downloads and installs a catalog, showing the progress here and in the status bar of N.I.N.A.</summary>
    private async Task DownloadAsync(Catalog catalog) {
        if (download != null) {
            return;
        }
        using var cancellation = new CancellationTokenSource();
        download = cancellation;
        OnPropertyChanged(nameof(Downloading));
        UpdateCatalogs();
        // created on the thread of the user interface, where it reports
        var progress = new Progress<DownloadProgress>(p => ShowProgress(p.Message, p.Fraction));
        ShowProgress($"Downloading {catalog.Name}", null);
        try {
            var directory = CatalogDirectoryPath;
            await Task.Run(() => Solver().InstallCatalog(catalog.Name, directory, progress, cancellation.Token));
            Notification.ShowSuccess($"Asterion: {catalog.Name} is installed");
        } catch (OperationCanceledException) {
            Notification.ShowInformation($"Asterion: the download of {catalog.Name} is cancelled. Downloading it again resumes where it stopped.");
        } catch (AsterionException e) {
            Logger.Error($"Asterion: {e.Message}");
            Notification.ShowError($"Asterion: {e.Message}");
        } finally {
            download = null;
            OnPropertyChanged(nameof(Downloading));
            DownloadStatus = "";
            statusMediator.StatusUpdate(new ApplicationStatus { Source = "Asterion", Status = string.Empty });
            await RefreshAsync();
        }
    }

    /// <summary>Cancels the download, which stops a moment later: until then, the status tells that it stops.</summary>
    private void StopDownload() {
        if (download is { IsCancellationRequested: false } running) {
            running.Cancel();
            ShowProgress("Stopping the download", null);
        }
    }

    private void ShowProgress(string message, double? fraction) {
        DownloadStatus = message;
        downloadFraction = fraction;
        OnPropertyChanged(nameof(DownloadFraction));
        OnPropertyChanged(nameof(DownloadIndeterminate));
        statusMediator.StatusUpdate(new ApplicationStatus {
            Source = "Asterion",
            Status = message,
            Progress = fraction ?? -1,
            MaxProgress = 1,
            ProgressType = ApplicationStatus.StatusProgressType.Percent
        });
    }

    private async Task RemoveAsync(Catalog catalog) {
        var list = catalogList;
        if (list == null || Downloading) {
            return;
        }
        var file = list.IndexFile(catalog.Name);
        if (MyMessageBox.Show($"Remove the catalog {catalog.Name}, {file}?", "Asterion", MessageBoxButton.YesNo, MessageBoxResult.No)
            != MessageBoxResult.Yes) {
            return;
        }
        try {
            // the solver maps the catalogs, whose files Windows can't delete then: closing it waits for the solves
            // which run, away from the thread of the interface
            await Task.Run(() => Solver().RemoveCatalog(file));
        } catch (Exception e) when (e is IOException or UnauthorizedAccessException or AsterionException) {
            Notification.ShowError($"Asterion: {catalog.Name} can't be removed: {e.Message}");
        }
        _ = RefreshAsync();
    }

    private void Set<T>(ref T field, T value, [CallerMemberName] string? name = null) {
        field = value;
        OnPropertyChanged(name);
    }

    private void OnPropertyChanged([CallerMemberName] string? name = null) {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
