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
  /** 高德业态 type（如「风景名胜;…」「购物服务;专卖店;…」），行为反馈映射偏好标签用 */
  poi_type?: string | null;
  /** 个性化推荐理由（阶段四回填，如"匹配你的偏好：历史文化"；未命中为空） */
  personal_note?: string | null;
  /** 数据来源：高德POI / 本地攻略 / 联网搜索 / LLM建议（需核实），由校验层填充 */
  source?: string | null;
}

export interface MealItem {
  name: string;
  meal_type: string;
  /** 开始时间 HH:mm（OPTIMIZATION_TODO P0①：LLM 生成餐点时间，前端据此与景点交错排序；缺省空串/无 → 归"其他安排"不编造） */
  start_time?: string | null;
  estimated_cost?: number;
  notes?: string | null;
  /** 数据来源：高德POI / 本地攻略 / LLM建议（需核实） */
  source?: string | null;
  /** 个性化推荐理由（口径统一轮：按 food 域标签回填，如"匹配你的偏好：火锅"；未命中为空） */
  personal_note?: string | null;
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
  /** 数据来源：高德路线估算 / 估算（LLM） */
  source?: string | null;
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

/** 个性化规划摘要（PLAN §5.5：后端确定性构建，非 LLM 文案；结果页顶部展示"本次规划结合了…"） */
export interface PersonalizationSummary {
  /** 命中的用户正偏好（如 历史文化/美食） */
  matched_preferences: string[];
  /** 应用的行程约束（来自请求参数：少辣 / 预算 ¥3200 等） */
  applied_constraints: string[];
  /** 新颖性说明（存在历史已体验候选时给出，否则空） */
  novelty_note?: string | null;
  /** 证据等级：PROFILE_AND_BEHAVIOR / QUESTIONNAIRE / HISTORY_INFER_ONLY / NONE */
  evidence_level?: string | null;
}

/** 被过滤/降级的候选（PLAN §5.6/§8.3：结果页"为什么没有推荐某些内容"，后端确定性构建） */
export interface FilteredCandidate {
  /** 候选名称（景点/餐厅） */
  name: string;
  /** 候选桶：景点/餐厅 */
  bucket?: string | null;
  /** 命中的回避标签（不感兴趣场景非空） */
  tag?: string | null;
  /** 未推荐的确定性原因（中文） */
  reason: string;
  /** 证据来源：USER_BEHAVIOR（不感兴趣反馈）/ HISTORY_TRIP（历史行程） */
  evidence?: string | null;
  /** 约束级别：HARD（硬约束沉底）/ SOFT（软降权） */
  severity?: string | null;
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
  /** 行程请求快照：用户提交的预算约束（null=未填），预算使用率计算用 */
  requested_budget?: number | null;
  /** 行程请求快照：出行人数 */
  travelers?: number | null;
  /** 行程请求快照：行程天数；历史行程缺失时前端回退不展示 */
  trip_days?: number | null;
  /** 个性化结构化摘要（有画像/约束依据时才挂载；无则整块隐藏） */
  personalization_summary?: PersonalizationSummary | null;
  /** 「为什么没有推荐某些内容」（有被过滤/降级的候选时才挂载；无则整块隐藏） */
  filtered_candidates?: FilteredCandidate[] | null;
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
  /**
   * 角色码（阶段二社区 / 阶段五角色细化）：
   * USER / CONTENT_REVIEWER / CITY_EDITOR / RECOMMENDATION_OPERATOR / SUPER_ADMIN（历史值 ADMIN）。
   * 登录响应与 /auth/me 提供，旧会话可能缺省。
   */
  role?: string | null;
  /** 角色中文名（如"内容审核员"），登录响应与 /auth/me 下发，仅供展示 */
  role_label?: string | null;
  /** 权限点集合（服务端下发，前端据此过滤菜单/按钮；服务端仍会二次校验） */
  permissions?: string[] | null;
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

/* ---------- 个性化阶段一：结构化画像 ---------- */

/** 画像主档（对应后端 user_profile 表；列表字段为逗号分隔串） */
export interface UserProfile {
  id: number;
  userId: string;
  /** 旅行风格（逗号分隔：自然风景/历史文化/美食探索/…） */
  travelStyles?: string | null;
  /** 节奏偏好（轻松/适中/紧凑） */
  pacePreference?: string | null;
  /** 住宿偏好（经济型/舒适型/高档型） */
  hotelPreference?: string | null;
  /** 预算参考（历史行程均值） */
  budgetPreference?: number | null;
  /** 口味偏好（逗号分隔） */
  foodPreferences?: string | null;
  /** 饮食约束（逗号分隔） */
  dietaryRestrictions?: string | null;
  /** 行为约束（逗号分隔） */
  behaviorNotes?: string | null;
  /** 去过的城市（逗号分隔） */
  visitedCities?: string | null;
  tripCount?: number;
  filledFromQuestionnaire?: number;
  profileVersion?: number;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** 偏好明细（带权重/置信度/来源） */
export interface ProfilePreference {
  id: number;
  userId: string;
  /** travel_style/pace/hotel/food/dietary/behavior */
  category: string;
  tag: string;
  weight: number;
  confidence: number;
  /** QUESTIONNAIRE / HISTORY_INFER / FEEDBACK */
  source: string;
  lastObservedAt?: string | null;
}

export interface ProfileResponse {
  profile: UserProfile | null;
  preferences: ProfilePreference[];
}

/**
 * 偏好问卷提交参数。
 * 契约：字段缺失 = 本次不涉及该域（保持现状）；空数组 / 空串 = 用户主动清空该域（撤销生效）。
 */
export interface QuestionnaireRequest {
  travelStyles?: string[];
  pace?: string | null;
  hotelLevel?: string | null;
  foodPreferences?: string[];
  dietaryRestrictions?: string[];
  behaviorNotes?: string[];
}

/* ---------- 个性化阶段二：行为反馈 ---------- */

/** 结果页行为反馈上报（景点收藏/不感兴趣/行程评分等；userId 由后端登录态解析） */
export interface BehaviorRequest {
  /** SPOT / RESTAURANT / TRIP */
  itemType: string;
  /** 对象 ID（景点 poi_id 或行程 trip_id），可空 */
  itemId?: string | null;
  /** 对象名称（留痕/反馈提示用） */
  itemName?: string | null;
  /** 景点高德业态 type（映射旅行风格标签） */
  poiType?: string | null;
  /** SAVE / DISLIKE / REPLACE / RATE 等 */
  actionType: string;
  /** RATE 行为的整体评分 1~5 */
  rating?: number | null;
  /** 低分时用户点选的不满意方面：pace/food/hotel/travel_style/other */
  aspects?: string[];
  /** 行为发生的行程 ID */
  tripId?: string | null;
  /** 负反馈粒度（PLAN 问题三）：ITEM 具体地点 / TYPE 这类地点 / TAG 主题标签 / DISTANCE / CROWDED / PRICE / PACE */
  reason?: string | null;
  /** reason=TAG 时点选的具体回避标签（如 ["购物"]） */
  tags?: string[];
}

/** 一次反馈对画像权重的实际调整（前端据此提示"反馈改变了什么"） */
export interface PreferenceAdjustment {
  category: string;
  tag: string;
  delta: number;
  weight: number;
  created?: boolean;
  protectedRow?: boolean;
}

export interface BehaviorResponse {
  success: boolean;
  message?: string;
  adjustments?: PreferenceAdjustment[];
}

/* ---------- 个性化阶段四：效果统计与推荐理由 ---------- */

/** 个性化效果指标（偏好命中率/负反馈率/平均满意度，来自 recommendation_log + user_behavior） */
export interface ProfileStats {
  /** 推荐日志条数（生成过的景点数） */
  recommendationCount: number;
  /** 偏好命中率（0~100）：命中画像正偏好的景点占比 */
  preferenceHitRate: number;
  /** 负反馈率（0~100）：(不感兴趣+替换)/全部行为 */
  dislikeRate: number;
  /** 满意度均值（1~5，行程评分 RATE 的平均；无评分为 0） */
  avgRating: number;
  /* ---------- 阶段三：帖子推荐流效果（post_feed_log 曝光 + POST 行为） ---------- */
  /** 帖子推荐曝光条数（分母） */
  postExposureCount?: number;
  /** 帖子推荐点击率（0~100，%） */
  postClickRate?: number;
  /** 帖子推荐收藏率（0~100，%） */
  postFavoriteRate?: number;
  /** 帖子推荐负反馈率（0~100，%） */
  postDislikeRate?: number;
}

export interface ProfileStatsResponse {
  success: boolean;
  stats?: ProfileStats | null;
  error?: string;
}

/* ---------- 产品化阶段一：推荐景点流 / 景点详情 / 收藏 / 旅行摘要 ---------- */

/** 推荐景点卡片项（GET /recommendations/spots 元素，字段为后端 snake_case 契约） */
export interface RecommendationItem {
  /** 系统景点 ID（spot_城市_poiId），详情/收藏路由用 */
  spot_id: string;
  /** 高德 POI ID（行为反馈 itemId 用） */
  poi_id: string;
  name: string;
  city: string;
  /** 高德业态 type（含分号分段，如「风景名胜;公园广场」；行为反馈映射偏好标签用） */
  category?: string | null;
  /** 图片 URL（高德；仅展示增强，不标官方） */
  image_url?: string | null;
  description?: string | null;
  tags?: string[] | null;
  /**
   * 内部综合排序分 0~1（含基础分 0.5 + 偏好增益 − 已去过惩罚），
   * 仅作排序/调试，**禁止**当"偏好匹配度"展示（排查报告 P0-1）
   */
  score?: number | null;
  /**
   * 本条是否真实命中个性化（仅"为你推荐"流内且正命中偏好的卡片为 true；
   * 无画像/未命中/攻略优先/最近更新/相关推荐/城市精选 → null/false）
   */
  personalized?: boolean | null;
  /** 真实偏好匹配分 0~1（personalized=true 时有值；= 偏好命中强度，不含基础分） */
  match_score?: number | null;
  /** 实际命中的偏好标签（personalized=true 时有值，供"匹配你的XX偏好"文案） */
  matched_preferences?: string[] | null;
  /** 确定性推荐理由（"匹配你的自然风景偏好" / "城市精选" / "本地攻略收录的真实景点"） */
  recommend_reason?: string | null;
  /** AMAP / RAG / AMAP_AND_RAG */
  source?: string | null;
  /** POI_ONLY / GUIDE_MATCHED / VERIFIED */
  data_quality?: string | null;
  is_collected?: boolean | null;
}

/** 推荐景点分页响应体 data（personalized=false 表示无画像降级热门） */
export interface RecommendationFeed {
  items: RecommendationItem[];
  personalized?: boolean | null;
  /** 画像版本（反馈后变化 → 相同请求结果应不同，前端可据此提示刷新） */
  profile_version?: number | null;
  page?: number | null;
  page_size?: number | null;
  total?: number | null;
}

/**
 * 首页「大家最近在规划」热门景点项（GET /home/trending-spots）。
 *
 * 这是**社会热度**而非个性化推荐（设计方案 §6.1）：因此这里**没有**匹配度/画像字段，
 * 前端不得把它包装成"适合你"；个性化如果要有，也只能另起一行做叠加标注。
 */
export interface TrendingSpotItem {
  /** 系统景点 ID（详情/收藏路由用） */
  spot_id: string;
  /** 事件里的 item_id（可能是 spot_id 也可能是 poi_id，仅调试用） */
  item_id: string;
  name: string;
  city: string;
  image_url?: string | null;
  data_quality?: string | null;
  /** 窗口内规划过该景点的人数；小样本保护命中时为 null（必须配合 sample_hidden 判断） */
  planning_users?: number | null;
  /** 窗口内该景点被生成进行程的总次数 */
  planning_count: number;
  saved_trip_count: number;
  favorite_count: number;
  /** true = 人数不足阈值，前端展示"近期有人规划"而不是具体数字 */
  sample_hidden: boolean;
  /** 趋势方向：UP 升温 / DOWN 降温 / FLAT 持平 */
  trend: string;
  /** 热度分（城市内归一化 + 按天半衰；跨城市已可比） */
  hot_score: number;
}

/** 首页热门景点响应体（GET /home/trending-spots 直出，无 success/data 包裹） */
export interface TrendingSpotFeed {
  items: TrendingSpotItem[];
  window_days: number;
  generated_at: string;
  /** 查询失败显式降级（延续看板口径：绝不伪装成"近期没有热门"） */
  degraded: boolean;
  errors: string[];
}

/** 景点详情（GET /spots/{id} data；含可信度/是否去过/收藏态/同城相关） */
export interface SpotDetail {
  spot_id: string;
  poi_id: string;
  name: string;
  city: string;
  address?: string | null;
  longitude?: number | null;
  latitude?: number | null;
  category?: string | null;
  image_url?: string | null;
  description?: string | null;
  tags?: string[] | null;
  source?: string | null;
  data_quality?: string | null;
  visited?: boolean | null;
  is_collected?: boolean | null;
  related_spots?: RecommendationItem[] | null;
}

/** 用户旅行摘要（GET /user/profile/summary data；Q5 修复：服务端实时聚合，不作废快照） */
export interface ProfileSummary {
  /** 历史行程总数（实时） */
  trip_count: number;
  /** 去重目的地列表（实时，按最近一次出现排序） */
  visited_cities: string[];
  /** 最近一次行程生成时间（无行程为 null） */
  latest_trip_at?: string | null;
  profile_version?: number | null;
  nickname?: string | null;
  /** 逗号分隔串，直接展示 */
  travel_styles?: string | null;
  pace_preference?: string | null;
  hotel_preference?: string | null;
  food_preferences?: string | null;
  budget_preference?: number | null;
}

/**
 * 景点收藏项（GET /user/spot-favorites items）。
 * 后端本批次已为实体补 @JsonProperty，统一为 snake_case 契约（与其余 API 一致）。
 */
export interface SpotFavorite {
  id: number;
  user_id: string;
  spot_id: string;
  poi_id?: string | null;
  name: string;
  city: string;
  image_url?: string | null;
  created_at?: string | null;
}

/* ---------- 产品化阶段二：社区（帖子 / 互动 / 评论 / 举报 / 审核） ---------- */

/** 帖子作者（对外只暴露 id/nickname） */
export interface PostAuthor {
  id: string;
  nickname?: string | null;
}

/** 帖子关联景点引用（提交/详情共用） */
export interface PostSpotRef {
  spot_id?: string | null;
  poi_id?: string | null;
  spot_name: string;
  image_url?: string | null;
}

/** 帖子类型（GUIDE/SPOT_RECOMMENDATION/ITINERARY/NOTE） */
export type PostType = "GUIDE" | "SPOT_RECOMMENDATION" | "ITINERARY" | "NOTE";

/** 帖子状态（DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/HIDDEN） */
export type PostStatus =
  | "DRAFT"
  | "PENDING_REVIEW"
  | "PUBLISHED"
  | "REJECTED"
  | "HIDDEN";

/** 帖子列表卡片（公开流/我的帖子/审核队列共用） */
export interface PostItem {
  id: number;
  title: string;
  summary?: string | null;
  cover_image?: string | null;
  city?: string | null;
  post_type: PostType;
  status: PostStatus;
  author: PostAuthor;
  like_count: number;
  favorite_count: number;
  comment_count: number;
  view_count: number;
  published_at?: string | null;
  created_at?: string | null;
  liked?: boolean | null;
  favorited?: boolean | null;
  /** 当前用户是否点过「不感兴趣」（后端于列表/详情回填；2026-09-18 前只由互动接口返回，刷新即丢） */
  disliked?: boolean | null;
  reject_reason?: string | null;
  mine?: boolean | null;
  /* ---------- P1-1 编辑版本化：公开版本 / 编辑版本分离 ---------- */
  /** 是否存在待审修改版本（已发布帖被编辑 → 修改稿独立待审，线上版本不变） */
  has_pending_revision?: boolean | null;
  /** 待审修改版本号（1=首版，2=第 2 版…） */
  pending_revision_no?: number | null;
  /* ---------- 阶段三：个性化推荐流返回（"为你推荐"排序时由后端填充） ---------- */
  /** 推荐理由（"匹配你的偏好：历史文化"） */
  recommend_reason?: string | null;
  /** 命中的画像标签（前端 🎯 徽标展示） */
  matched_tags?: string[] | null;
  /** 个性化得分（0~1，排序列透出） */
  score?: number | null;
  /* ---------- 阶段四：内容质量（提交时后端计算，管理队列展示） ---------- */
  /** 内容质量分 0~100 */
  quality_score?: number | null;
  /** 是否低质内容（低于阈值标记） */
  low_quality?: boolean | null;
}

/** 帖子详情（extends 列表卡片 + 正文/天数/预算/节奏/关联景点） */
export interface PostDetail extends PostItem {
  content: string;
  travel_days?: number | null;
  budget?: number | null;
  pace?: string | null;
  spots?: PostSpotRef[] | null;
  /** 待审修改版本内容快照（作者/审核员可见；无待审版本为 null） */
  pending_revision?: PendingRevision | null;
}

/** 待审修改版本内容快照（P1-1 版本化，"线上版本 vs 待审版本"对比用） */
export interface PendingRevision {
  id: number;
  revision_no: number;
  status: string;
  title: string;
  summary?: string | null;
  content: string;
  cover_image?: string | null;
  city?: string | null;
  travel_days?: number | null;
  budget?: number | null;
  pace?: string | null;
  post_type?: string | null;
  spots?: PostSpotRef[] | null;
  reject_reason?: string | null;
  edited_at?: string | null;
  editor?: string | null;
}

/** 帖子分页响应体 */
export interface PostPage {
  items: PostItem[];
  total: number;
  page: number;
}

/**
 * 创建/编辑帖子入参。
 *
 * <p>字段名必须是 <b>snake_case</b>：后端 PostCreateRequest/PostUpdateRequest 用
 * `@JsonProperty("cover_image")` 声明契约、并不做命名策略转换（axios 也在请求拦截器里
 * 只加 Authorization，不改 key）。此处曾写成 camelCase（coverImage/travelDays/postType），
 * 导致这三个字段被 Spring Boot 当未知属性静默丢弃 —— 封面不显示、天数与类型丢失。
 */
export interface PostPayload {
  title: string;
  summary?: string;
  content: string;
  cover_image?: string;
  city?: string;
  travel_days?: number;
  budget?: number;
  pace?: string;
  post_type?: PostType;
  spots?: PostSpotRef[];
}

/** 评论项 */
export interface CommentItem {
  id: number;
  post_id: number;
  parent_id?: number | null;
  content: string;
  deleted?: boolean | null;
  author?: PostAuthor | null;
  created_at?: string | null;
  mine?: boolean | null;
  /**
   * 当前访问者是否有权删除（评论作者 / 楼主 / 管理员；已删评论恒 false）。
   * 权限规则由后端统一下发，前端不再自己拼 —— 避免「后端能删、前端不显示按钮」的不一致。
   */
  can_delete?: boolean | null;
}

export interface CommentPage {
  items: CommentItem[];
  total: number;
  page: number;
}

/** 举报入参 */
export interface ReportPayload {
  target_type: "POST" | "COMMENT";
  target_id: number;
  reason: string;
  detail?: string;
}

/** 管理端举报队列项（pending 与 history 共用；history 带 status/处理人/时间） */
export interface ReportItem {
  id: number;
  target_type: "POST" | "COMMENT";
  target_id: number;
  target_snapshot: string;
  reason: string;
  detail?: string | null;
  reporter_id: string;
  reporter_name: string;
  created_at?: string | null;
  /** PENDING/RESOLVED/DISMISSED（举报历史展示） */
  status?: string | null;
  handled_by?: string | null;
  handled_at?: string | null;
  /** 处理备注（管理员举报处理时填写） */
  handle_note?: string | null;
}

/* ---------- 阶段四：城市专题页 ---------- */

/** 城市专题聚合（GET /city-topic?city=xxx data；复用推荐流/公开流语义，不新增算法） */
export interface CityTopic {
  city?: string | null;
  /** 该城市已入库景点数 */
  spot_total: number;
  /** 该城市已发布帖子数 */
  post_total: number;
  /** 精选景点（攻略质量优先：GUIDE_MATCHED/VERIFIED 在前，上限 8；第一版无真实热度埋点，不称"热门"） */
  hot_spots: RecommendationItem[];
  /** 该城最新公开攻略（上限 6） */
  recent_posts: PostItem[];
}

/* ---------- 阶段四：关注 + 用户旅行主页 ---------- */

/** 用户旅行主页（GET /users/{userId}/home data；只含公开数据：昵称/统计/已发布帖子） */
export interface UserHome {
  user_id: string;
  nickname?: string | null;
  created_at?: string | null;
  follower_count: number;
  following_count: number;
  /** 已发布帖子数 */
  post_count: number;
  /** 是否本人（前端隐藏关注按钮） */
  mine: boolean;
  /** 当前查看者是否已关注该用户 */
  following: boolean;
  posts: PostItem[];
}

/** 关注/取关/状态响应（POST/DELETE /users/{id}/follow 与 /follow/status） */
export interface FollowResponse {
  success: boolean;
  following?: boolean | null;
  created?: boolean | null;
}
