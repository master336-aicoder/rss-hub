import { http } from "./http";

/** Mirrors `SystemStatusController.StatusView` and its nested records. Counts are one row's facts, not a history. */
export interface SystemInfo {
  osName: string;
  osVersion: string;
  osArch: string;
  javaVersion: string;
  javaVendor: string;
  jvmStartedAt: string;
  uptimeSeconds: number;
}

export interface JvmInfo {
  heapUsedBytes: number;
  heapCommittedBytes: number;
  heapMaxBytes: number;
  liveThreads: number;
  loadedClasses: number;
}

export interface DatabaseInfo {
  up: boolean;
  productName?: string;
  productVersion?: string;
  feeds: number;
  entries: number;
  rules: number;
  notifications: number;
}

export interface ServicesInfo {
  fetchEnabled: boolean;
  notifyEnabled: boolean;
  taskQueueByState: Record<string, number>;
  notificationsByState: Record<string, number>;
  lastFetchedAt?: string;
}

export interface SystemStatus {
  now: string;
  system: SystemInfo;
  jvm: JvmInfo;
  database: DatabaseInfo;
  services: ServicesInfo;
}

export const systemApi = {
  status: () => http.get<SystemStatus>("/api/system/status"),
};
