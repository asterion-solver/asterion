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

using System.Runtime.InteropServices;
using System.Text;

namespace Asterion.Nina.Core;

// The structures and the functions of asterion.h, the header of the native library. The structures have the
// layout of the C ones, which AsterionLibrary checks against the library when it loads it.

[StructLayout(LayoutKind.Sequential)]
internal struct NativeOptions {
    public double Ra;
    public double Dec;
    public double Radius;
    public double ScaleLow;
    public double ScaleHigh;
    public double Fov;
    public double PixelSize;
    public double Timeout;
    public double Sigma;
    public double Epoch;
    public int Parity;
    public int SipOrder;
    public int Binning;
    public int MaxStars;
    public int QuadStars;
    public int Padding;
}

[StructLayout(LayoutKind.Sequential)]
internal unsafe struct NativeSolution {
    public double Ra;
    public double Dec;
    public double Scale;
    public double Rotation;
    public double FieldWidth;
    public double FieldHeight;
    public double Rms;
    public double LogOdds;
    public double FocalLength;
    public double PixelSize;
    public double FwhmPixels;
    public double FwhmArcsec;
    public fixed double Crpix[2];
    public fixed double Crval[2];
    public fixed double Cd[4];
    public fixed double SipA[36];
    public fixed double SipB[36];
    public fixed double SipAp[64];
    public fixed double SipBp[64];
    public double ReadMs;
    public double DetectMs;
    public double SolveMs;
    public int SipOrder;
    public int SipInverseOrder;
    public int Flipped;
    public int MatchedStars;
    public int Stars;
    public fixed byte Index[64];
    public int Padding;
}

[StructLayout(LayoutKind.Sequential)]
internal unsafe struct NativeCatalog {
    public fixed byte Name[64];
    public fixed byte Description[256];
    public int Installed;
    public int Available;
    public long Size;
    public long Stars;
    public long Quads;
    public double MagnitudeLimit;
    public double MinQuadArcmin;
    public double MaxQuadDeg;
    public fixed byte Error[256];
}

[StructLayout(LayoutKind.Sequential)]
internal unsafe struct NativeProgress {
    public int Event;
    public int Padding;
    public long Completed;
    public long Total;
    public long Bytes;
    public double Fraction;
    public byte* Step;
    public byte* Unit;
    public byte* Message;
}

/// <summary>The functions of the library, and the helpers which read and write its strings.</summary>
internal sealed unsafe class NativeFunctions {
    public const int Uint16 = 2;
    public const int Solved = 1;
    public const int Error = -1;
    public const int Cancelled = -2;
    public const int EventStep = 1;
    public const int EventProgress = 2;
    public const int EventDetail = 3;
    public const int EventInfo = 5;

    public readonly delegate* unmanaged<nint, nint*, nint*, int> CreateIsolate;
    public readonly delegate* unmanaged<nint, nint*, int> AttachThread;
    public readonly delegate* unmanaged<nint, int> DetachThread;
    public readonly delegate* unmanaged<nint, nint> CurrentThread;
    public readonly delegate* unmanaged<nint, byte*, nuint, int> Version;
    public readonly delegate* unmanaged<nint, byte*, nuint, int> DefaultCatalogDirectory;
    public readonly delegate* unmanaged<nint, byte*, NativeCatalog*, int, int> Catalogs;
    public readonly delegate* unmanaged<nint, byte*, byte*, delegate* unmanaged<NativeProgress*, nint, int>, nint, int> InstallCatalog;
    public readonly delegate* unmanaged<nint, byte*, nint*, int> Open;
    public readonly delegate* unmanaged<nint, nint, int> Close;
    public readonly delegate* unmanaged<nint, NativeOptions*, int> DefaultOptions;
    public readonly delegate* unmanaged<nint, nint, void*, int, int, int, long, int, NativeOptions*, NativeSolution*, nint*, int*, int> SolveCancellable;
    public readonly delegate* unmanaged<nint, nint, void> FreeResult;
    public readonly delegate* unmanaged<nint, byte*, nuint, int> LastError;
    public readonly delegate* unmanaged<nint, byte*, int> SizeOf;

    public NativeFunctions(nint library) {
        nint Export(string name) => NativeLibrary.GetExport(library, name);
        CreateIsolate = (delegate* unmanaged<nint, nint*, nint*, int>) Export("graal_create_isolate");
        AttachThread = (delegate* unmanaged<nint, nint*, int>) Export("graal_attach_thread");
        DetachThread = (delegate* unmanaged<nint, int>) Export("graal_detach_thread");
        CurrentThread = (delegate* unmanaged<nint, nint>) Export("graal_get_current_thread");
        Version = (delegate* unmanaged<nint, byte*, nuint, int>) Export("asterion_version");
        DefaultCatalogDirectory = (delegate* unmanaged<nint, byte*, nuint, int>) Export("asterion_default_catalog_directory");
        Catalogs = (delegate* unmanaged<nint, byte*, NativeCatalog*, int, int>) Export("asterion_catalogs");
        InstallCatalog = (delegate* unmanaged<nint, byte*, byte*, delegate* unmanaged<NativeProgress*, nint, int>, nint, int>) Export("asterion_install_catalog");
        Open = (delegate* unmanaged<nint, byte*, nint*, int>) Export("asterion_open");
        Close = (delegate* unmanaged<nint, nint, int>) Export("asterion_close");
        DefaultOptions = (delegate* unmanaged<nint, NativeOptions*, int>) Export("asterion_default_options");
        SolveCancellable = (delegate* unmanaged<nint, nint, void*, int, int, int, long, int, NativeOptions*, NativeSolution*, nint*, int*, int>)
            Export("asterion_solve_cancellable");
        FreeResult = (delegate* unmanaged<nint, nint, void>) Export("asterion_result_free");
        LastError = (delegate* unmanaged<nint, byte*, nuint, int>) Export("asterion_error");
        SizeOf = (delegate* unmanaged<nint, byte*, int>) Export("asterion_sizeof");
    }

    /// <summary>A string for the library: UTF-8, null terminated, or null for NULL.</summary>
    public static byte[]? Utf8(string? text) => text == null ? null : Encoding.UTF8.GetBytes(text + '\0');

    /// <summary>A string which the library wrote in a buffer of a structure, null terminated.</summary>
    public static string Text(byte* text, int capacity) {
        var length = 0;
        while (length < capacity && text[length] != 0) {
            length++;
        }
        return Encoding.UTF8.GetString(text, length);
    }

    /// <summary>A string which the library gives, null terminated, or an empty string for NULL.</summary>
    public static string Text(byte* text) => text == null ? "" : Marshal.PtrToStringUTF8((nint) text) ?? "";

    /// <summary>
    /// A string which a function copies into a buffer: it tells the size it needs, which may be larger than the
    /// buffer, and is called again with a larger one then.
    /// </summary>
    /// <returns>the string, or null if the function failed</returns>
    public static string? Copy(Func<nint, nuint, int> copy) {
        var buffer = new byte[4096];
        while (true) {
            int length;
            fixed (byte* pointer = buffer) {
                length = copy((nint) pointer, (nuint) buffer.Length);
            }
            if (length < 0) {
                return null;
            }
            if (length < buffer.Length) {
                return Encoding.UTF8.GetString(buffer, 0, length);
            }
            buffer = new byte[length + 1];
        }
    }
}
