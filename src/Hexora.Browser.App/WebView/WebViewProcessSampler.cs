using System.ComponentModel;
using System.Diagnostics;
using Microsoft.Web.WebView2.Core;

namespace Hexora.Browser.App.WebView;

public static class WebViewProcessSampler
{
    public static WebViewProcessSnapshot Capture(CoreWebView2Environment environment)
    {
        ArgumentNullException.ThrowIfNull(environment);

        long workingSetBytes = 0;
        long privateMemoryBytes = 0;
        long processorTimeMilliseconds = 0;
        var sampledProcessCount = 0;
        var processIds = environment.GetProcessInfos()
            .Select(info => checked((int)info.ProcessId))
            .Distinct()
            .ToArray();

        foreach (var processId in processIds)
        {
            try
            {
                using var process = Process.GetProcessById(processId);
                workingSetBytes += process.WorkingSet64;
                privateMemoryBytes += process.PrivateMemorySize64;
                processorTimeMilliseconds += (long)process.TotalProcessorTime.TotalMilliseconds;
                sampledProcessCount++;
            }
            catch (ArgumentException)
            {
                // A WebView process can exit between the environment snapshot and OS sampling.
            }
            catch (InvalidOperationException)
            {
                // A WebView process can exit between the environment snapshot and OS sampling.
            }
            catch (Win32Exception)
            {
                // Process metrics can be unavailable under a restricted Windows policy.
            }
        }

        using var hostProcess = Process.GetCurrentProcess();
        return new WebViewProcessSnapshot(
            processIds.Length,
            sampledProcessCount,
            ToMegabytes(workingSetBytes),
            ToMegabytes(privateMemoryBytes),
            processorTimeMilliseconds,
            ToMegabytes(hostProcess.WorkingSet64));
    }

    private static long ToMegabytes(long bytes) => bytes / (1024 * 1024);
}

public sealed record WebViewProcessSnapshot(
    int ReportedProcessCount,
    int SampledProcessCount,
    long WorkingSetMegabytes,
    long PrivateMemoryMegabytes,
    long TotalProcessorTimeMilliseconds,
    long HostWorkingSetMegabytes);
