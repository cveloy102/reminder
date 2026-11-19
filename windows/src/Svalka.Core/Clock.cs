namespace Svalka.Core;

public static class Clock
{
    public static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public static DateTime ToLocal(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;

    public static long FromLocal(DateTime local) =>
        new DateTimeOffset(DateTime.SpecifyKind(local, DateTimeKind.Local)).ToUnixTimeMilliseconds();
}
