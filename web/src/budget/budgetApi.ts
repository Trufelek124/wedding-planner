import { api, unwrap, type Schemas } from '../api/client.ts'

/** Planned, committed and paid, in total and per category; every amount in the currency's smallest unit. */
export type Budget = Schemas['Budget']
export type BudgetLine = Schemas['BudgetLine']
export type BudgetChange = Schemas['BudgetChange']

export async function fetchBudget(signal?: AbortSignal): Promise<Budget> {
  return unwrap(await api.GET('/api/budget', { signal }))
}

/** @throws ApiError `changed-meanwhile` when someone else saved the budget after it was loaded */
export async function changeBudget(change: BudgetChange): Promise<Budget> {
  return unwrap(await api.PUT('/api/budget', { body: change }))
}
