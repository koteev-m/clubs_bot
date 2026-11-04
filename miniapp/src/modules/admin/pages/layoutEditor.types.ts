/** The editor keeps client BIGINT identifiers opaque; legacy admin callers use numbers. */
export type LayoutId = number | string;
export interface EditorTable<Id extends LayoutId = number> {
  id: Id;
  hallId: Id;
  tableNumber: number;
  label: string;
  capacity: number;
  x: number | null;
  y: number | null;
  bookingLinked?: boolean;
  bookingDeposit?: string | null;
}
export interface EditorHall<Id extends LayoutId = number> {
  id: Id;
  clubId: Id;
  name: string;
  isActive: boolean;
  layoutRevision: number | string;
  tableCount?: number;
}
export interface TableFields {
  tableNumber: number;
  label: string;
  capacity: number;
  x: number;
  y: number;
}
export type PhysicalTableFields = Omit<TableFields, 'x' | 'y'> & {
  minDeposit?: string;
};

export type PlanPoint = { x: number; y: number };

export interface PhysicalTablesApi<Id extends LayoutId> {
  reservedNumbers: () => number[];
  create: (hall: Id, fields: PhysicalTableFields) => Promise<EditorTable<Id>>;
  createBatch: (
    hall: Id,
    tableNumbers: number[],
    capacity: number,
  ) => Promise<EditorTable<Id>[]>;
  update: (
    hall: Id,
    table: Id,
    fields: PhysicalTableFields,
  ) => Promise<EditorTable<Id>>;
  place: (
    hall: Id,
    table: Id,
    point: PlanPoint | null,
  ) => Promise<EditorTable<Id>>;
}

export interface HallEditorApi<Id extends LayoutId = number> {
  physical?: PhysicalTablesApi<Id>;
  listHallTables: (id: Id, signal?: AbortSignal) => Promise<EditorTable<Id>[]>;
  createHallTable: (id: Id, fields: TableFields) => Promise<EditorTable<Id>>;
  updateHallTable: (
    hall: Id,
    table: Id,
    fields: Partial<TableFields>,
  ) => Promise<EditorTable<Id>>;
  deleteHallTable?: (hall: Id, table: Id) => Promise<void>;
  uploadHallPlan: (hall: Id, file: File) => Promise<unknown>;
  fetchHallPlanBlob: (
    club: Id,
    hall: Id,
    etag?: string | null,
    signal?: AbortSignal,
  ) => Promise<{
    status: number;
    blob?: Blob;
    etag?: string | null;
  }>;
}
export interface ClubHallsApi<Id extends LayoutId = number> {
  listHalls: (id: Id, signal?: AbortSignal) => Promise<EditorHall<Id>[]>;
  createHall: (
    club: Id,
    fields: { name: string; geometryJson: string; isActive: boolean },
  ) => Promise<EditorHall<Id>>;
  updateHall: (
    hall: Id,
    fields: { name?: string; geometryJson?: string },
  ) => Promise<EditorHall<Id>>;
  deleteHall?: (hall: Id) => Promise<void>;
  makeHallActive?: (hall: Id) => Promise<EditorHall<Id>>;
}
