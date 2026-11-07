import type { ConfigurationView } from "./configuration.hooks";

export const moduleIds = [
  "stamps",
  "early_arrival",
  "badges",
  "prizes",
  "contests",
  "table_loyalty",
  "music",
] as const;
export type ModuleId = (typeof moduleIds)[number];
export type ModuleStatus =
  | "configured"
  | "disabled"
  | "requires_setup"
  | "unavailable";
export const flagNames = [
  "stampsEnabled",
  "earlyEnabled",
  "badgesEnabled",
  "prizesEnabled",
  "contestsEnabled",
  "tablesLoyaltyEnabled",
] as const;
export type ModuleSettingsView = ConfigurationView & {
  name: string;
  settingsPresent: boolean;
  earlyWindowMinutes: number | null;
  flags: Record<(typeof flagNames)[number], boolean>;
  modules: {
    id: ModuleId;
    status: ModuleStatus;
    blockedBy: string;
    savedEnabled: boolean | null;
    activationAvailable: false;
  }[];
};

export const validModules = (view: ModuleSettingsView) =>
  typeof view.name === "string" &&
  typeof view.settingsPresent === "boolean" &&
  (view.earlyWindowMinutes === null ||
    (Number.isInteger(view.earlyWindowMinutes) &&
      view.earlyWindowMinutes >= 0 &&
      view.earlyWindowMinutes <= 2147483647)) &&
  view.flags != null &&
  flagNames.every((field) => typeof view.flags[field] === "boolean") &&
  Array.isArray(view.modules) &&
  view.modules.length === moduleIds.length &&
  moduleIds.every(
    (id) => view.modules.filter((item) => item.id === id).length === 1,
  ) &&
  view.modules.every(
    (item) =>
      ["configured", "disabled", "requires_setup", "unavailable"].includes(
        item.status,
      ) &&
      typeof item.blockedBy === "string" &&
      (item.savedEnabled === null || typeof item.savedEnabled === "boolean") &&
      item.activationAvailable === false,
  );
