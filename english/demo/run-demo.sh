#!/bin/bash

# Quiz Platform Demo Runner
# This script helps you run the complete demo

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Print banner
echo -e "${CYAN}${BOLD}"
echo "╔════════════════════════════════════════════════════════════╗"
echo "║         Quiz Platform Demo Runner                          ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# Check prerequisites
echo -e "${BOLD}Checking prerequisites...${NC}"

# Check Java
if ! command -v java &> /dev/null; then
    echo -e "${RED}❌ Java not found. Please install Java 17 or higher.${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Java found: $(java -version 2>&1 | head -n 1)${NC}"

# Check Node.js
if ! command -v node &> /dev/null; then
    echo -e "${RED}❌ Node.js not found. Please install Node.js.${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Node.js found: $(node --version)${NC}"

# Check Redis
if ! command -v redis-cli &> /dev/null && ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ Neither Redis nor Docker found. Please install one of them.${NC}"
    exit 1
fi

# Check if Redis is running
if redis-cli ping &> /dev/null; then
    echo -e "${GREEN}✅ Redis is running${NC}"
elif docker ps | grep -q redis; then
    echo -e "${GREEN}✅ Redis is running (Docker)${NC}"
else
    echo -e "${YELLOW}⚠️  Redis is not running${NC}"
    echo -e "${YELLOW}   Starting Redis with Docker...${NC}"
    
    if command -v docker &> /dev/null; then
        docker run -d --name quiz-redis -p 6379:6379 redis:7-alpine
        sleep 2
        echo -e "${GREEN}✅ Redis started${NC}"
    else
        echo -e "${RED}❌ Cannot start Redis. Please start it manually.${NC}"
        exit 1
    fi
fi

echo ""

# Install demo dependencies
echo -e "${BOLD}Installing demo dependencies...${NC}"
cd "$(dirname "$0")"
if [ ! -d "node_modules" ]; then
    npm install
    echo -e "${GREEN}✅ Dependencies installed${NC}"
else
    echo -e "${GREEN}✅ Dependencies already installed${NC}"
fi

echo ""

# Menu
echo -e "${BOLD}Select demo mode:${NC}"
echo "  1) Single user demo (simple)"
echo "  2) Multi-user demo (3 users, same instance)"
echo "  3) Multi-instance demo (3 users, 3 instances)"
echo "  4) Custom demo"
echo ""
read -p "Enter choice [1-4]: " choice

case $choice in
    1)
        echo -e "${CYAN}${BOLD}Starting Single User Demo...${NC}"
        echo -e "${YELLOW}This will start 1 user connecting to localhost:8080${NC}"
        echo ""
        echo -e "${YELLOW}Make sure the application is running on port 8080!${NC}"
        echo -e "${YELLOW}Press Ctrl+C to stop${NC}"
        echo ""
        sleep 2
        node quiz-client.js
        ;;
    
    2)
        echo -e "${CYAN}${BOLD}Starting Multi-User Demo (Same Instance)...${NC}"
        echo -e "${YELLOW}This will start 3 users connecting to localhost:8080${NC}"
        echo ""
        echo -e "${YELLOW}Make sure the application is running on port 8080!${NC}"
        echo -e "${YELLOW}Press Ctrl+C to stop all clients${NC}"
        echo ""
        sleep 2
        
        # Start 3 clients with different user IDs
        USER_ID=alice ANSWER_INTERVAL=4000 node quiz-client.js &
        PID1=$!
        sleep 1
        
        USER_ID=bob ANSWER_INTERVAL=5000 node quiz-client.js &
        PID2=$!
        sleep 1
        
        USER_ID=charlie ANSWER_INTERVAL=6000 node quiz-client.js &
        PID3=$!
        
        # Wait for all clients
        wait $PID1 $PID2 $PID3
        ;;
    
    3)
        echo -e "${CYAN}${BOLD}Starting Multi-Instance Demo...${NC}"
        echo -e "${YELLOW}This will start 3 users connecting to different instances${NC}"
        echo ""
        echo -e "${RED}${BOLD}IMPORTANT:${NC}${YELLOW} Make sure you have 3 instances running:${NC}"
        echo -e "${YELLOW}  - Instance A on port 8080${NC}"
        echo -e "${YELLOW}  - Instance B on port 8081${NC}"
        echo -e "${YELLOW}  - Instance C on port 8082${NC}"
        echo ""
        read -p "Press Enter when ready, or Ctrl+C to cancel..."
        echo ""
        
        # Start 3 clients connecting to different instances
        USER_ID=alice SERVER_URL=http://localhost:8080 ANSWER_INTERVAL=4000 node quiz-client.js &
        PID1=$!
        sleep 1
        
        USER_ID=bob SERVER_URL=http://localhost:8081 ANSWER_INTERVAL=5000 node quiz-client.js &
        PID2=$!
        sleep 1
        
        USER_ID=charlie SERVER_URL=http://localhost:8082 ANSWER_INTERVAL=6000 node quiz-client.js &
        PID3=$!
        
        # Wait for all clients
        wait $PID1 $PID2 $PID3
        ;;
    
    4)
        echo -e "${CYAN}${BOLD}Custom Demo${NC}"
        echo ""
        read -p "Server URL [http://localhost:8080]: " server_url
        server_url=${server_url:-http://localhost:8080}
        
        read -p "Quiz ID [quiz123]: " quiz_id
        quiz_id=${quiz_id:-quiz123}
        
        read -p "User ID [random]: " user_id
        
        read -p "Answer interval (ms) [3000]: " interval
        interval=${interval:-3000}
        
        read -p "Max answers [10]: " max_answers
        max_answers=${max_answers:-10}
        
        echo ""
        echo -e "${YELLOW}Starting custom demo...${NC}"
        
        if [ -z "$user_id" ]; then
            SERVER_URL=$server_url QUIZ_ID=$quiz_id ANSWER_INTERVAL=$interval MAX_ANSWERS=$max_answers node quiz-client.js
        else
            SERVER_URL=$server_url QUIZ_ID=$quiz_id USER_ID=$user_id ANSWER_INTERVAL=$interval MAX_ANSWERS=$max_answers node quiz-client.js
        fi
        ;;
    
    *)
        echo -e "${RED}Invalid choice${NC}"
        exit 1
        ;;
esac

