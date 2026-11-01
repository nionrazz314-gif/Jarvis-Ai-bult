# JARVIS AI — Android Studio Project

এটি JARVIS AI-এর প্রথম Android source-code সংস্করণ। এই ZIP-এ APK নয়, Android Studio-তে build করার সম্পূর্ণ project source দেওয়া হয়েছে।

## কী আছে
- Android app shell (Kotlin + WebView)
- JARVIS chat interface
- বাংলা speech-to-text (Android Speech Recognizer)
- Text-to-speech
- OpenAI ChatGPT API, Google Gemini API ও Anthropic Claude API integration
- Provider অনুযায়ী API key/model settings
- Chat screen ও local settings

## APK বানানোর নিয়ম
1. কম্পিউটারে Android Studio (একটি সাম্প্রতিক stable version) ইনস্টল করুন।
2. ZIP extract করুন।
3. Android Studio > Open > `JarvisAI_Android_Project` ফোল্ডারটি খুলুন।
4. Gradle sync শেষ হতে দিন এবং SDK Platform 35 ইনস্টল করুন।
5. Build > Build Bundle(s) / APK(s) > Build APK(s) চাপুন।
6. APK পাবেন `app/build/outputs/apk/debug/app-debug.apk`-এ।

## API key
- Gemini: Google AI Studio থেকে API key নিন।
- OpenAI: OpenAI Platform থেকে API key নিন।
- Claude: Anthropic Console থেকে API key নিন।
API ব্যবহার সাধারণত usage-based এবং ফ্রি সীমা/মূল্য provider অনুযায়ী বদলায়। API key অ্যাপের local storage/preferences-এ রাখা হচ্ছে; এটি production-grade secure vault নয়। নিজের ফোনে ব্যবহার করুন, key কাউকে দেবেন না।

## সীমাবদ্ধতা ও পরবর্তী ধাপ
- ChatGPT/Gemini/Claude API key ছাড়া ব্যবহার করা যাবে না।
- GitHub হলো code hosting/developer platform; এটি নিজে সাধারণ chat model নয়। GitHub Copilot-এর public general chat API এখানে সরাসরি যুক্ত করা হয়নি।
- n8n, Flowise, Open WebUI এবং ComfyUI server/self-hosted service; তাদের আলাদা server setup ও endpoint লাগবে। এই starter build-এ সেগুলো এখনো wired-up নয়।
- PocketClaws-এর নির্দিষ্ট product/API ও Android compatibility নিশ্চিত না হওয়া পর্যন্ত নিরাপদে সংযোগ করা হয়নি।
- Android speech recognition-এর জন্য ডিভাইসে speech service এবং microphone permission প্রয়োজন হতে পারে।
- এই source project এখানে compile করে APK যাচাই করা হয়নি; Android Studio দিয়ে build করতে হবে।

## ভিডিওতে দেখা টুল
ভিডিওতে ComfyUI Engine, Open WebUI, n8n Automation Engine এবং Flowise AI Agent Builder দেখা যায়। এগুলোকে পরের সংস্করণে server URL ও authentication configure করে যুক্ত করা যাবে।


## শুধু Android মোবাইল দিয়ে APK বানানো (GitHub Actions)
এই প্রজেক্টে `.github/workflows/build-apk.yml` যোগ করা হয়েছে। Android Studio/PC ছাড়াই ফোনের Chrome থেকে:
1. GitHub-এ সাইন ইন করে New repository তৈরি করুন (Public বা Private)।
2. ZIP extract করে সব ফাইল repository-তে upload করুন। `.github/workflows/build-apk.yml` ফাইলটিও যেন থাকে।
3. Repository-র Actions tab খুলে `Build JARVIS AI APK` workflow নির্বাচন করুন।
4. `Run workflow` চাপুন; build শেষ হলে নিচে Artifacts থেকে `JARVIS-AI-debug-APK` ডাউনলোড করুন।
5. ZIP artifact extract করে `app-debug.apk` ফাইলে ট্যাপ করুন। Android চাইলে browser/file manager থেকে Install unknown apps অনুমতি দিন।

নোট: GitHub Actions-এর free usage quota/limits প্রযোজ্য। এই workflow-টি source project compile করে; build সফল হয়েছে বলে আগাম নিশ্চয়তা দেওয়া যাচ্ছে না। Gradle wrapper অনুপস্থিত থাকলে runner-এর installed Gradle ব্যবহার করা হবে।
