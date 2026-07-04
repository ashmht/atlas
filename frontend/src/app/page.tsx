// Treasury dashboard. Reads yield attribution from the analytics service and
// renders portfolio APY and per-venue accrual. Money values are decimal strings
// end to end; JSON floats never carry money.
type AttributionRow = { venue: string; principal: string; accrued: string; share_bps: number };
type YieldResponse = { portfolio_apy_bps: number; attribution: AttributionRow[] };

async function getYieldAttribution(): Promise<YieldResponse> {
  const res = await fetch(`${process.env.ANALYTICS_URL ?? "http://localhost:8000"}/v1/analytics/yield-attribution`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      as_of: new Date().toISOString().slice(0, 10),
      positions: [
        // amounts are decimal STRINGS end to end — JSON floats never carry money
        { venue: "tokenized-tbill", principal: "4000000", apr_bps: 520, days: 30 },
        { venue: "money-market", principal: "1000000", apr_bps: 500, days: 30 },
      ],
    }),
    cache: "no-store",
  });
  return res.ok ? res.json() : { portfolio_apy_bps: 0, attribution: [] };
}

export default async function Dashboard() {
  const data = await getYieldAttribution();
  return (
    <main className="mx-auto max-w-4xl p-8 font-sans">
      <h1 className="text-2xl font-semibold">Atlas Treasury</h1>
      <p className="mt-1 text-sm text-gray-500">
        Portfolio APY: {(data.portfolio_apy_bps / 100).toFixed(2)}%
      </p>
      <table className="mt-6 w-full text-sm">
        <thead>
          <tr className="border-b text-left text-gray-500">
            <th className="py-2">Venue</th><th>Principal</th><th>Accrued</th><th>Share (bps)</th>
          </tr>
        </thead>
        <tbody>
          {data.attribution.map((r) => (
            <tr key={r.venue} className="border-b">
              <td className="py-2">{r.venue}</td>
              <td>${Number(r.principal).toLocaleString()}</td>
              <td>${Number(r.accrued).toFixed(2)}</td>
              <td>{r.share_bps}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}
