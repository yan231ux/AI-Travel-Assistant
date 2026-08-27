export interface TripRequestPayload {
  destination: string;
  start_date: string;
  end_date: string;
  travelers: number;
  budget: number;
  preferences: string[];
  pace?: string | null;
  dietary_preferences: string[];
  hotel_level?: string | null;
  special_notes?: string | null;
}

export interface SpotItem {
  name: string;
  start_time?: string | null;
  end_time?: string | null;
  description?: string | null;
  estimated_cost?: number;
  location?: string | null;
  image_url?: string | null;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  poi_id?: string | null;
}

export interface MealItem {
  name: string;
  meal_type: string;
  estimated_cost?: number;
  notes?: string | null;
}

export interface HotelItem {
  name: string;
  level?: string | null;
  estimated_cost?: number;
  location?: string | null;
  address?: string | null;
  latitude?: number | null;
  longitude?: number | null;
}

export interface TransportItem {
  mode: string;
  from_place?: string | null;
  to_place?: string | null;
  estimated_cost?: number;
  duration?: string | null;
  distance_km?: number | null;
  estimated_minutes?: number | null;
}

export interface DayPlan {
  day_index: number;
  date?: string | null;
  theme?: string | null;
  spots: SpotItem[];
  meals: MealItem[];
  hotel?: HotelItem | null;
  transport: TransportItem[];
  notes: string[];
}

export interface BudgetBreakdown {
  transport: number;
  hotel: number;
  meals: number;
  tickets: number;
  other: number;
  total: number;
}

export interface Itinerary {
  trip_id: string;
  destination: string;
  summary: string;
  days: DayPlan[];
  estimated_budget: number;
  budget_breakdown: BudgetBreakdown;
  tips: string[];
  source_notes: string[];
  /** 生成时刻的天气快照；旧行程可能缺失，缺失时前端回退实时拉取 */
  weather?: WeatherForecastResponse | null;
}

export interface TripSaveResponse {
  message: string;
  trip_id: string;
}

export interface TripSummaryItem {
  trip_id: string;
  destination: string;
  summary: string;
  created_at?: string | null;
  updated_at?: string | null;
}

export interface TripListResponse {
  total: number;
  items: TripSummaryItem[];
}

export interface TripDetailResponse {
  trip_id: string;
  itinerary: Itinerary;
  created_at?: string | null;
  updated_at?: string | null;
  /** Agent 推理轨迹（历史行程回放用） */
  trace: AgentTraceStep[];
}

export interface WeatherForecastDay {
  date?: string | null;
  week?: string | null;
  day_weather?: string | null;
  night_weather?: string | null;
  day_temp?: string | null;
  night_temp?: string | null;
  day_wind?: string | null;
  night_wind?: string | null;
}

export interface WeatherForecastResponse {
  city: string;
  province?: string | null;
  adcode?: string | null;
  report_time?: string | null;
  days: WeatherForecastDay[];
}

export interface AgentTraceStep {
  step: number;
  thought: string;
  action?: string | null;
  observation?: string | null;
  tool_calls: Array<Record<string, any>>;
}

export interface AgentTraceResponse {
  success: boolean;
  itinerary?: Itinerary | null;
  trace: AgentTraceStep[];
  collected_data: Record<string, any>;
  token_usage: Record<string, number>;
  errors: string[];
}

/** 登录用户信息（user.id 为字符串，与 trip_record.user_id 同型） */
export interface User {
  id: string;
  username: string;
  nickname?: string | null;
}

export interface AuthResponse {
  success: boolean;
  message: string;
  token: string;
  user: User;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  nickname?: string | null;
}
