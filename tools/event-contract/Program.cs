using System.Text.Json;
using Domain.Insights;

// Read actual Android requests and invoke the target service's validation/message conversion.
// HTTP 200 alone is insufficient: InsightController silently drops invalid input.
if (args.Length != 1) throw new ArgumentException("Expected Android payload file");
var text = File.ReadAllText(args[0]);
if (text.Contains("sendToExperiment") || text.Contains("do-not-retain")) throw new Exception("Unexpected retired/private field");
var insights = JsonSerializer.Deserialize<Insight[]>(text, new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
var messages = new List<InsightMessage>();
foreach (var insight in insights)
{
    if (!insight.IsValid()) throw new Exception("Target service would silently drop Android payload");
    if (insight.User!.CustomizedProperties.Length != 0) throw new Exception("Private attributes retained");
    insight.AppendInsightMessages("phase5-fixture", messages);
}
if (messages.Count != 3 || messages.Count(x => x.Event == InsightMessage.FlagValueEvent) != 1)
    throw new Exception("Expected one evaluation and two Track messages");
foreach (var message in messages.Where(x => x.Event == "CustomEvent"))
{
    using var props = JsonDocument.Parse(message.Properties);
    if (props.RootElement.GetProperty("applicationType").GetString() != "Android") throw new Exception("Wrong appType");
}
Console.WriteLine($"PHASE5_CONTRACT_PASS {insights.Length} valid payloads, {messages.Count} target messages");
